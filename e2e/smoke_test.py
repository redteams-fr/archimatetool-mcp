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
check(len(tools) == 18, f"expected 18 tools, got {len(tools)}")

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
