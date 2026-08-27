-- wrk script for POST /msg/send_msg.
--
-- Every call needs a unique msgID (SendMsgReq.msgID is the primary key of the MySQL queue tables /
-- t_msg_record), so a static wrk body would collide on the same row. request() builds a fresh JSON
-- body per call instead. Point LOADTEST_TEMPLATE_ID at a template whose channel is a safe,
-- side-effect-free stub (SMS with Tencent disabled) -- never a real billable/rate-limited channel,
-- because wrk fires thousands of these per second.
--
-- The API always answers HTTP 200 and signals the outcome in the JSON body ("code":0 == accepted &
-- durably enqueued; anything else == rejected). A raw req/sec count would therefore include dropped
-- messages, so response()/done() tally body-level accept vs reject to measure *reliable* throughput.

local template_id = assert(os.getenv("LOADTEST_TEMPLATE_ID"),
    "set LOADTEST_TEMPLATE_ID env var to the activated load-test template id")
-- A per-run tag keeps this run's msgIDs disjoint from any prior run's rows (msgID is the PK), so we
-- can count exactly what THIS run persisted without deleting existing data.
local run_id = os.getenv("LOADTEST_RUN_ID") or tostring(os.time())
-- wrk.format() takes a path, not a URL, so the target path cannot be inherited from the URL
-- argument. It is set explicitly here to stay correct when the app runs under a context path
-- (server.servlet.context-path, e.g. /msgcenter) instead of at the server root.
local send_path = os.getenv("LOADTEST_PATH") or "/msg/send_msg"

-- Collected in the main VM (setup runs there) so done() can read each worker's counters.
local threads = {}

function setup(thread)
    -- Give each worker a stable, unique id (used to build collision-free msgIDs).
    thread:set("tid", #threads + 1)
    table.insert(threads, thread)
end

-- init() runs once inside each worker thread's own Lua VM, after setup() has injected `tid`.
function init(args)
    counter = 0
    accepted = 0
    rejected = 0
    thread_tag = tostring(tid)
    wrk.method = "POST"
    wrk.headers["Content-Type"] = "application/json"
end

function request()
    counter = counter + 1
    local msg_id = string.format("wrk-%s-%s-%d", run_id, thread_tag, counter)
    local body = string.format(
        '{"to":"13800000000","subject":"LoadTest","priority":2,' ..
        '"templateId":"%s","templateData":{"seq":"%d"},"msgID":"%s"}',
        template_id, counter, msg_id)
    return wrk.format(nil, send_path, nil, body)
end

function response(status, headers, body)
    if status == 200 and body ~= nil and string.find(body, '"code":0', 1, true) then
        accepted = accepted + 1
    else
        rejected = rejected + 1
    end
end

function done(summary, latency, requests)
    local total_accepted = 0
    local total_rejected = 0
    for _, t in ipairs(threads) do
        total_accepted = total_accepted + t:get("accepted")
        total_rejected = total_rejected + t:get("rejected")
    end
    io.write("\n--- reliability summary ---\n")
    io.write(string.format("accepted  (code:0, durably enqueued)     : %d\n", total_accepted))
    io.write(string.format("rejected  (enqueue failed, NOT silent)   : %d\n", total_rejected))
    io.write(string.format("total HTTP responses                     : %d\n", summary.requests))
end
