#!/usr/bin/env python3
"""End-to-end smoke test: drives the MCP server running inside Archi like an MCP client would."""
import json
import sys
import time
import urllib.error
import urllib.request

URL, TOKEN = sys.argv[1], sys.argv[2]
session = None
next_id = 0


def wait_for_server(timeout=300):
    start = time.time()
    while time.time() - start < timeout:
        try:
            urllib.request.urlopen(URL, timeout=5)
        except urllib.error.HTTPError:
            print(f"  ok  server up after {time.time() - start:.0f}s")
            return  # any HTTP answer means the server is listening
        except OSError:
            time.sleep(2)
    raise SystemExit(f"MCP server not reachable after {timeout}s")


wait_for_server()


def rpc(method, params=None, notify=False):
    global session, next_id
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        msg["params"] = params
    if not notify:
        next_id += 1
        msg["id"] = next_id
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": "Bearer " + TOKEN,
        "MCP-Protocol-Version": "2025-06-18",
    }
    if session:
        headers["Mcp-Session-Id"] = session
    req = urllib.request.Request(URL, json.dumps(msg).encode(), headers, method="POST")
    with urllib.request.urlopen(req, timeout=60) as resp:
        if resp.headers.get("Mcp-Session-Id"):
            session = resp.headers["Mcp-Session-Id"]
        body = resp.read()
        return json.loads(body) if body else None


def call(tool, expect_error=False, **arguments):
    result = rpc("tools/call", {"name": tool, "arguments": arguments})["result"]
    text = result["content"][0]["text"]
    if result["isError"] != expect_error:
        raise AssertionError(f"{tool}: isError={result['isError']}: {text}")
    print(f"  ok  {tool}{' (expected error)' if expect_error else ''}")
    return text if expect_error else json.loads(text) if text.startswith(("{", "[")) else text


def check(cond, message):
    if not cond:
        raise AssertionError(message)


# --- auth
try:
    urllib.request.urlopen(urllib.request.Request(URL, b"{}", {"Content-Type": "application/json"}, method="POST"))
    raise AssertionError("request without token was accepted")
except urllib.error.HTTPError as e:
    check(e.code == 401, f"expected 401, got {e.code}")
print("  ok  unauthenticated request rejected")

# --- lifecycle
init = rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                          "clientInfo": {"name": "e2e", "version": "1"}})["result"]
check(init["serverInfo"]["name"] == "archi-mcp", init)
check(session, "no session id")
rpc("notifications/initialized", notify=True)
print(f"  ok  initialize (server {init['serverInfo']['version']}, protocol {init['protocolVersion']})")

tools = [t["name"] for t in rpc("tools/list")["result"]["tools"]]
print(f"  ok  tools/list: {', '.join(tools)}")
check(len(tools) == 19, f"expected 19 tools, got {len(tools)}")

# --- types
types = call("list_element_types")
check("BusinessActor" in types["element_types"]["business"], types)
check("ServingRelationship" in types["relationship_types"], types)

# --- model + elements
model = call("create_model", name="E2E model", purpose="Created by the e2e test")
mid = model["id"]
models = call("list_models")["models"]
check(any(m["id"] == mid for m in models), models)

crm = call("create_element", model_id=mid, type="ApplicationComponent", name="CRM",
           documentation="Customer relationship management", properties={"owner": "Sales IT"})
sales = call("create_element", model_id=mid, type="business-actor", name="Sales Team")
proc = call("create_element", model_id=mid, type="Business Process", name="Handle order")
call("create_element", expect_error=True, model_id=mid, type="Spaceship", name="X")

# --- relationships (valid, lenient type name, and invalid)
serving = call("create_relationship", type="Serving", source_id=crm["id"], target_id=proc["id"])
check(serving["type"] == "ServingRelationship", serving)
assignment = call("create_relationship", type="AssignmentRelationship", source_id=sales["id"], target_id=proc["id"])
err = call("create_relationship", expect_error=True, type="Assignment", source_id=proc["id"], target_id=crm["id"])
check("Valid relationship types" in err, err)

# --- read back
found = call("search_elements", model_id=mid, query="crm")
check(found["total"] == 1 and found["results"][0]["id"] == crm["id"], found)
by_prop = call("search_elements", model_id=mid, property_key="owner", property_value="sales it")
check(by_prop["total"] == 1, by_prop)
rels = call("search_elements", model_id=mid, type="Serving")
check(rels["total"] == 1, rels)
details = call("get_element", id=proc["id"])
check(len(details["relationships"]) == 2, details)

upd = call("update_element", id=crm["id"], name="CRM (Salesforce)", properties={"owner": None, "vendor": "Salesforce"})
check(upd["name"] == "CRM (Salesforce)" and upd["properties"] == {"vendor": "Salesforce"}, upd)

# Edits go through Archi's command stack, which marks the model dirty (and makes them undoable)
state = next(m for m in call("list_models")["models"] if m["id"] == mid)
check(state["unsaved_changes"] is True and state["elements"] == 3 and state["relationships"] == 2, state)

# --- view
view = call("create_view", model_id=mid, name="Order handling", viewpoint="layered")
n1 = call("add_to_view", view_id=view["id"], element_id=proc["id"])
n2 = call("add_to_view", view_id=view["id"], element_id=crm["id"], x=24, y=200)
check(len(n2["connections_added"]) == 1, n2)  # serving relationship drawn automatically
n3 = call("add_to_view", view_id=view["id"], element_id=sales["id"], add_connections=False)
check(n3["connections_added"] == [], n3)
conn = call("add_relationship_to_view", view_id=view["id"], relationship_id=assignment["id"])
call("add_relationship_to_view", expect_error=True, view_id=view["id"], relationship_id=assignment["id"])

content = call("get_view", id=view["id"])
check(len(content["nodes"]) == 3 and len(content["connections"]) == 2, content)
views = call("list_views", model_id=mid)["views"]
check(any(v["id"] == view["id"] for v in views), views)

# --- styles: colours are set on the view's node/connection, undoable, and reported by get_view
styled = call("set_view_object_style", view_id=view["id"], object_id=n1["node_id"],
              fill_color="#FFCC00", line_color="336699", font_color="#000000", alpha=200)
check(styled["style"] == {"fill_color": "#ffcc00", "alpha": 200, "line_color": "#336699", "font_color": "#000000"}, styled)
reset = call("set_view_object_style", view_id=view["id"], object_id=n1["node_id"], line_color="")
check("line_color" not in reset["style"] and reset["style"]["fill_color"] == "#ffcc00", reset)
cstyle = call("set_view_object_style", view_id=view["id"], object_id=conn["connection_id"], line_color="#ff0000")
check(cstyle["style"] == {"line_color": "#ff0000"}, cstyle)
call("set_view_object_style", expect_error=True, view_id=view["id"], object_id=conn["connection_id"], fill_color="#ff0000")
call("set_view_object_style", expect_error=True, view_id=view["id"], object_id=n1["node_id"], fill_color="red")
call("set_view_object_style", expect_error=True, view_id=view["id"], object_id=n1["node_id"], alpha=300)
call("set_view_object_style", expect_error=True, view_id=view["id"], object_id=n1["node_id"])
styled_view = call("get_view", id=view["id"])
node1 = next(n for n in styled_view["nodes"] if n["node_id"] == n1["node_id"])
check(node1["style"] == {"fill_color": "#ffcc00", "alpha": 200, "font_color": "#000000"}, node1)
check(all("style" not in n for n in styled_view["nodes"] if n["node_id"] != n1["node_id"]), styled_view)

# --- batch: elements linked by local refs, drawn on a view, as one command
bview = call("create_view", model_id=mid, name="Online orders")
batch = call("create_batch", view_id=bview["id"], elements=[
    {"ref": "portal", "type": "ApplicationComponent", "name": "Web portal", "properties": {"owner": "Web"}},
    {"ref": "api", "type": "Application Interface", "name": "Orders API"},
    {"ref": "cust", "type": "BusinessActor", "name": "Customer", "x": 400, "y": 24},
], relationships=[
    {"ref": "comp", "type": "Composition", "source": "portal", "target": "api"},
    {"type": "Serving", "source": "api", "target": "cust"},
    {"type": "Serving", "source": "portal", "target": proc["id"]},  # proc is not on this view
])
check([e["ref"] for e in batch["elements"]] == ["portal", "api", "cust"], batch)
check(batch["relationships"][0]["source"]["id"] == batch["elements"][0]["id"], batch)
check(batch["relationships"][2]["target"]["id"] == proc["id"], batch)
check(len(batch["view"]["nodes_added"]) == 3 and len(batch["view"]["connections_added"]) == 2, batch)
check(len(batch["view"]["relationships_not_drawn"]) == 1, batch)
check(batch["view"]["nodes_added"][2]["bounds"]["x"] == 400, batch)
bcontent = call("get_view", id=bview["id"])
check(len(bcontent["nodes"]) == 3 and len(bcontent["connections"]) == 2, bcontent)

# atomic: one invalid item, nothing created; the error names the item
err = call("create_batch", expect_error=True, model_id=mid,
           elements=[{"ref": "ghost", "type": "Node", "name": "Ghost node"}],
           relationships=[{"type": "Serving", "source": "ghost", "target": "nowhere"}])
check("relationships[0]" in err and "nothing was created" in err, err)
check(call("search_elements", model_id=mid, query="Ghost node")["total"] == 0, "partial batch was created")
err = call("create_batch", expect_error=True, model_id=mid,
           elements=[{"ref": "a", "type": "Node", "name": "A"}, {"ref": "a", "type": "Node", "name": "B"}])
check("elements[1]" in err and "used twice" in err, err)
call("create_batch", expect_error=True, model_id=mid)
state = next(m for m in call("list_models")["models"] if m["id"] == mid)
check(state["elements"] == 6 and state["relationships"] == 5, state)

# nesting: a Grouping of this call as parent (coordinates relative to it), then an existing node as parent
nview = call("create_view", model_id=mid, name="Layers")
nested = call("create_batch", view_id=nview["id"], elements=[
    {"ref": "biz", "type": "Grouping", "name": "Business", "x": 20, "y": 20, "width": 400, "height": 120},
    {"ref": "clerk", "type": "BusinessRole", "name": "Clerk", "parent": "biz", "x": 20, "y": 40, "width": 120, "height": 55},
    {"ref": "ship", "type": "BusinessProcess", "name": "Ship order", "parent": "biz"},
], relationships=[{"type": "Assignment", "source": "clerk", "target": "ship"}])
check(len(nested["view"]["connections_added"]) == 1, nested)
group_node = nested["view"]["nodes_added"][0]
check(group_node["ref"] == "biz" and group_node["bounds"]["width"] == 400, nested)
layers = call("get_view", id=nview["id"])
check(len(layers["nodes"]) == 1 and len(layers["nodes"][0]["children"]) == 2, layers)
check(layers["nodes"][0]["children"][0]["bounds"]["x"] == 20, layers)
more = call("create_batch", view_id=nview["id"], elements=[
    {"type": "BusinessEvent", "name": "Order paid", "parent": group_node["node_id"]}])
check(len(call("get_view", id=nview["id"])["nodes"][0]["children"]) == 3, more)
err = call("create_batch", expect_error=True, view_id=nview["id"],
           elements=[{"type": "BusinessEvent", "name": "Lost", "parent": "later"},
                     {"ref": "later", "type": "Grouping", "name": "Later"}])
check("elements[0]" in err and "parent" in err, err)
err = call("create_batch", expect_error=True, model_id=mid, elements=[{"type": "Node", "name": "N", "parent": "biz"}])
check("needs view_id" in err, err)

# reuse: a replayed batch reuses elements, relationships and nodes; styles and hidden relationships
rview = call("create_view", model_id=mid, name="Replay")
replay = dict(view_id=rview["id"], reuse_existing=True, elements=[
    {"ref": "apps", "type": "Grouping", "name": "Applications", "x": 20, "y": 20, "width": 320, "height": 120},
    {"ref": "portal", "type": "ApplicationComponent", "name": " web PORTAL ", "parent": "apps", "x": 20, "y": 35,
     "fill_color": "#C5E0B4", "documentation": "ignored on reuse"},
    {"ref": "erp", "type": "ApplicationComponent", "name": "ERP", "parent": "apps", "x": 170, "y": 35,
     "fill_color": "#f8cbad", "alpha": 200},
], relationships=[
    {"type": "Flow", "source": "portal", "target": "erp", "name": "F1"},
    {"type": "Aggregation", "source": "apps", "target": "portal", "draw": False},
    {"type": "Aggregation", "source": "apps", "target": "erp", "draw": False},
])
first = call("create_batch", **replay)
check([e.get("reused", False) for e in first["elements"]] == [False, True, False], first)
check(first["elements"][1]["id"] == batch["elements"][0]["id"], first)  # the "Web portal" of the batch above
check(len(first["view"]["nodes_added"]) == 3 and first["view"]["nodes_reused"] == [], first)
check(len(first["view"]["connections_added"]) == 1, first)  # the flow; the aggregations are not drawn
second = call("create_batch", **replay)
check(all(e.get("reused") for e in second["elements"]) and all(r.get("reused") for r in second["relationships"]), second)
check(second["view"]["nodes_added"] == [] and len(second["view"]["nodes_reused"]) == 3, second)
check(second["view"]["connections_added"] == [], second)
rcontent = call("get_view", id=rview["id"])
check(len(rcontent["nodes"]) == 1 and len(rcontent["connections"]) == 1, rcontent)
kids = {c["element"]["name"]: c for c in rcontent["nodes"][0]["children"]}
check(kids["Web portal"]["style"] == {"fill_color": "#c5e0b4"}, kids)
check(kids["ERP"]["style"] == {"fill_color": "#f8cbad", "alpha": 200}, kids)
portal = call("get_element", id=batch["elements"][0]["id"])
check(portal["properties"] == {"owner": "Web"} and not portal.get("documentation"), portal)
check(call("search_elements", model_id=mid, type="Aggregation")["total"] == 2, "aggregations duplicated")
# ambiguous reuse, duplicates in the call, and styles without a view are errors
call("create_element", model_id=mid, type="ApplicationComponent", name="erp ")
err = call("create_batch", expect_error=True, model_id=mid, reuse_existing=True,
           elements=[{"type": "ApplicationComponent", "name": "ERP"}])
check("elements[0]" in err and "2 existing" in err, err)
err = call("create_batch", expect_error=True, model_id=mid, reuse_existing=True,
           elements=[{"type": "Node", "name": "Twin"}, {"type": "Node", "name": "twin"}])
check("elements[1]" in err and "same type and name" in err, err)
err = call("create_batch", expect_error=True, model_id=mid, elements=[{"type": "Node", "name": "N", "fill_color": "#ffffff"}])
check("need view_id" in err, err)

call("open_view", view_id=view["id"])
sel = call("get_selection")
check("selection" in sel, sel)

err = call("save_model", expect_error=True, model_id=mid)
check("never been saved" in err, err)

# --- validation (Archi's validator): an element on no view must be reported
orphan = call("create_element", model_id=mid, type="Node", name="Orphan server")
report = call("validate_model", model_id=mid)
flagged = [i for i in report["issues"] if i.get("object", {}).get("id") == orphan["id"]]
check(flagged, f"orphan element not reported: {report}")
check(flagged[0]["kind"] in report["explanations"], report)
total = sum(report["counts"].values())
check(report["returned"] == total and not report["truncated"], report)
print(f"      counts {report['counts']}, orphan reported as {flagged[0]['severity']}: {flagged[0]['kind']}")
limited = call("validate_model", model_id=mid, limit=1)
check(limited["returned"] == 1 and limited["truncated"] == (total > 1), limited)
errors_only = call("validate_model", model_id=mid, severity="error")
check(all(i["severity"] == "error" for i in errors_only["issues"]), errors_only)
check(errors_only["counts"] == report["counts"], errors_only)
call("validate_model", expect_error=True, model_id=mid, severity="fatal")

print("\nAll end-to-end checks passed.")
