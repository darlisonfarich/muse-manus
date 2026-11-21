// GERADO por gadgetlink/tools/gen_phone_catalog.py a partir de src/test/resources/mika_tools.json
// (o tools/list real do servidor mika). Não editar à mão: rodar o gerador; o CommandCatalogTest
// confere este arquivo contra o golden. Os 29 comandos são STUBS da medição da Etapa 1: anunciados só
// com includeStubs e sem nenhum efeito — todo handler devolve not_implemented.
package com.darlisonfarich.gadgetlink.commands

object PhoneCatalog {
    /** Comando gadget -> ferramenta mika correspondente (1:1). */
    val MIKA: Map<String, String> = linkedMapOf(
        "phone.run_task" to "mika_run_task",
        "phone.task_status" to "mika_task_status",
        "phone.wait_task" to "mika_wait_task",
        "phone.answer_task" to "mika_answer",
        "phone.steer_task" to "mika_steer_task",
        "phone.cancel_task" to "mika_cancel_task",
        "phone.list_tasks" to "mika_list_tasks",
        "phone.device_state" to "mika_device_state",
        "phone.wake" to "mika_wake_device",
        "phone.unlock" to "mika_unlock_device",
        "phone.status" to "phone_status",
        "phone.get_state" to "phone_get_state",
        "phone.reconcile" to "phone_reconcile",
        "phone.get_tree" to "phone_observe",
        "phone.get_screenshot" to "phone_screenshot",
        "phone.list_apps" to "phone_list_apps",
        "phone.approval_issue" to "phone_approval_issue",
        "phone.open_app" to "phone_launch_app",
        "phone.tap" to "phone_tap",
        "phone.type_text" to "phone_type",
        "phone.scroll" to "phone_scroll",
        "phone.swipe" to "phone_swipe",
        "phone.gesture" to "phone_gesture",
        "phone.press_key" to "phone_key",
        "phone.volume" to "phone_volume",
        "phone.media" to "phone_media",
        "phone.press_back" to "phone_back",
        "phone.run_batch" to "phone_run",
        "phone.cancel" to "phone_cancel",
    )

    fun stubs(): List<CommandSpec> = listOf(
        CommandSpec.stub(
            name = "phone.run_task",
            description = "Hand Mika a task to do on the Android phone. Mika runs its own agent on the phone (apps, web, files) and records the task in a conversation the user can see in the app. Returns a task_id right away; follow it with mika_wait_task. Payments and confirmations are never approved over MCP.",
            required = linkedMapOf(
                "goal" to ParamSpec(ParamType.STRING, "What to get done, in plain words, as you would tell a person."),
            ),
            optional = linkedMapOf(
                "request_id" to ParamSpec(ParamType.STRING, "Choose a lowercase UUID before submitting. It is also the task_id for status after a lost reply. Reuse only for the same request. [format uuid]"),
                "conversation" to ParamSpec(ParamType.STRING, "Optional: continue a conversation returned by an earlier task so Mika keeps its context."),
            ),
            timeoutMs = 15_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.task_status",
            description = "Current state of a task: status, latest progress note, the question it is waiting on, or the final answer.",
            required = linkedMapOf(
                "task_id" to ParamSpec(ParamType.STRING, "The task_id returned by mika_run_task."),
            ),
            optional = emptyMap(),
            timeoutMs = 10_000L,
            writes = false,
        ),
        CommandSpec.stub(
            name = "phone.wait_task",
            description = "Wait until the task's status changes or it finishes, up to timeout_s seconds (default 30, at most 50), then return its state. Call again to keep waiting.",
            required = linkedMapOf(
                "task_id" to ParamSpec(ParamType.STRING, "The task_id returned by mika_run_task."),
            ),
            optional = linkedMapOf(
                "timeout_s" to ParamSpec(ParamType.INTEGER, "Longest wait in seconds. [1–50]"),
            ),
            timeoutMs = 55_000L,
            writes = false,
        ),
        CommandSpec.stub(
            name = "phone.answer_task",
            description = "Answer the question Mika asked in this task (status waiting_for_answer). Cannot approve payments, confirmations or steps handed to the user; those stay on the phone.",
            required = linkedMapOf(
                "task_id" to ParamSpec(ParamType.STRING, "The task_id returned by mika_run_task."),
                "text" to ParamSpec(ParamType.STRING, "The answer."),
            ),
            optional = emptyMap(),
            timeoutMs = 10_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.steer_task",
            description = "Add instructions to a running remote task, keeping its task_id and context. Accepted means queued, not executed; check steers in mika_task_status for the message_id. Does not answer questions, approve payments, resume paused tasks, or start a new task.",
            required = linkedMapOf(
                "task_id" to ParamSpec(ParamType.STRING, "The task_id returned by mika_run_task."),
                "text" to ParamSpec(ParamType.STRING, "Instructions to add to the running task. [min 1 chars; max 4000 chars]"),
            ),
            optional = emptyMap(),
            timeoutMs = 10_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.cancel_task",
            description = "Stop a remote task.",
            required = linkedMapOf(
                "task_id" to ParamSpec(ParamType.STRING, "The task_id returned by mika_run_task."),
            ),
            optional = emptyMap(),
            timeoutMs = 10_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.list_tasks",
            description = "Recent remote tasks, newest first.",
            required = emptyMap(),
            optional = linkedMapOf(
                "limit" to ParamSpec(ParamType.INTEGER, "How many (default 10). [1–50]"),
            ),
            timeoutMs = 10_000L,
            writes = false,
        ),
        CommandSpec.stub(
            name = "phone.device_state",
            description = "Read phone screen/lock state and whether local remote-unlock permission is enabled. No credentials are returned.",
            required = emptyMap(),
            optional = emptyMap(),
            timeoutMs = 10_000L,
            writes = false,
        ),
        CommandSpec.stub(
            name = "phone.wake",
            description = "Wake the main phone screen. Requires the phone's remote wake/unlock setting. Does not unlock it.",
            required = emptyMap(),
            optional = emptyMap(),
            timeoutMs = 15_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.unlock",
            description = "Wake and unlock the phone using only permission and credentials configured locally on the phone. Call before mika_run_task, or when a task is paused because the phone is locked. A failed credential attempt blocks further attempts until reconfigured on the phone. Cannot approve payments or bypass authentication.",
            required = emptyMap(),
            optional = emptyMap(),
            timeoutMs = 30_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.status",
            description = "Read session state and whether the local kill switch permits writes.",
            required = emptyMap(),
            optional = emptyMap(),
            timeoutMs = 5_000L,
            writes = false,
        ),
        CommandSpec.stub(
            name = "phone.get_state",
            description = "Alias for phone_status: session, write controls and current task contention.",
            required = emptyMap(),
            optional = emptyMap(),
            timeoutMs = 5_000L,
            writes = false,
        ),
        CommandSpec.stub(
            name = "phone.reconcile",
            description = "Read-only lookup of prior request_id in the owner's durable audit journal, or a phone_run checkpoint. Never executes or retries; use phone_status then fresh phone_observe.",
            required = linkedMapOf(
                "request_id" to ParamSpec(ParamType.STRING, "Unique UUID idempotency key for this action. Never reuse for a different operation. [max 64 chars]"),
                "kind" to ParamSpec(ParamType.STRING, "action or batch"),
            ),
            optional = linkedMapOf(
                "commands" to ParamSpec(ParamType.ARRAY, "For batch only: exact original ordered commands, compared by fingerprint; never executed. [up to 30 items of object]"),
            ),
            timeoutMs = 10_000L,
            writes = false,
        ),
        CommandSpec.stub(
            name = "phone.get_tree",
            description = "Read sanitized Android accessibility elements and current application. Password screens require the user.",
            required = emptyMap(),
            optional = emptyMap(),
            timeoutMs = 30_000L,
            writes = false,
        ),
        CommandSpec.stub(
            name = "phone.get_screenshot",
            description = "Read a protected-screen-aware screenshot, compressed to under 1 MiB; password screens are denied.",
            required = emptyMap(),
            optional = emptyMap(),
            timeoutMs = 30_000L,
            writes = false,
        ),
        CommandSpec.stub(
            name = "phone.list_apps",
            description = "List installed application package names and labels.",
            required = emptyMap(),
            optional = emptyMap(),
            timeoutMs = 15_000L,
            writes = false,
        ),
        CommandSpec.stub(
            name = "phone.approval_issue",
            description = "Mint a signed, single-use chat approval token (GER PR-6) bound to one exact phone action (tool + arguments). Redeemed by the action gate at execution; expires in minutes; never authorizes payment. Minting executes nothing.",
            required = linkedMapOf(
                "tool" to ParamSpec(ParamType.STRING, "External phone action tool name, e.g. phone_tap"),
                "arguments" to ParamSpec(ParamType.OBJECT, "The exact arguments the action will be called with (without request_id)."),
            ),
            optional = emptyMap(),
            timeoutMs = 10_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.open_app",
            description = "Open an installed application in the agent's virtual display.",
            required = linkedMapOf(
                "package" to ParamSpec(ParamType.STRING, "Exact Android application package name"),
                "request_id" to ParamSpec(ParamType.STRING, "Unique UUID idempotency key for this action. Never reuse for a different operation. [max 64 chars]"),
            ),
            optional = linkedMapOf(
                "cold" to ParamSpec(ParamType.BOOLEAN, "Restart the app first (default false)."),
                "approval_token" to ParamSpec(ParamType.STRING, "Single-use chat approval token (GER PR-6) minted by phone_approval_issue for exactly this tool + arguments. Authorizes that one gated action once; never authorizes payment. [max 512 chars]"),
            ),
            timeoutMs = 30_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.tap",
            description = "Tap an element ref/snapshot pair or normalized 0?999 coordinates. Consequential taps are denied by the action gate.",
            required = linkedMapOf(
                "request_id" to ParamSpec(ParamType.STRING, "Unique UUID idempotency key for this action. Never reuse for a different operation. [max 64 chars]"),
            ),
            optional = linkedMapOf(
                "ref" to ParamSpec(ParamType.STRING, "Element identifier from phone_observe"),
                "snapshotId" to ParamSpec(ParamType.STRING, "Snapshot identifier corresponding to ref"),
                "x" to ParamSpec(ParamType.INTEGER, "Normalized X [0–999]"),
                "y" to ParamSpec(ParamType.INTEGER, "Normalized Y [0–999]"),
                "target" to ParamSpec(ParamType.STRING, "Description of coordinate target [max 80 chars]"),
                "clicks" to ParamSpec(ParamType.INTEGER, "Tap count [1–2]"),
                "approval_token" to ParamSpec(ParamType.STRING, "Single-use chat approval token (GER PR-6) minted by phone_approval_issue for exactly this tool + arguments. Authorizes that one gated action once; never authorizes payment. [max 512 chars]"),
            ),
            timeoutMs = 30_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.type_text",
            description = "Type text in a non-secret field. Enter may submit content; the same local gate still applies.",
            required = linkedMapOf(
                "text" to ParamSpec(ParamType.STRING, "Text to type [max 10000 chars]"),
                "request_id" to ParamSpec(ParamType.STRING, "Unique UUID idempotency key for this action. Never reuse for a different operation. [max 64 chars]"),
            ),
            optional = linkedMapOf(
                "ref" to ParamSpec(ParamType.STRING, "Element identifier from phone_observe"),
                "snapshotId" to ParamSpec(ParamType.STRING, "Snapshot identifier corresponding to ref"),
                "x" to ParamSpec(ParamType.INTEGER, "Normalized X [0–999]"),
                "y" to ParamSpec(ParamType.INTEGER, "Normalized Y [0–999]"),
                "pressEnter" to ParamSpec(ParamType.BOOLEAN, "Press Enter after typing (may submit content)."),
                "approval_token" to ParamSpec(ParamType.STRING, "Single-use chat approval token (GER PR-6) minted by phone_approval_issue for exactly this tool + arguments. Authorizes that one gated action once; never authorizes payment. [max 512 chars]"),
            ),
            timeoutMs = 30_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.scroll",
            description = "Scroll the virtual screen in a chosen direction.",
            required = linkedMapOf(
                "direction" to ParamSpec(ParamType.STRING, "up, down, left or right"),
                "request_id" to ParamSpec(ParamType.STRING, "Unique UUID idempotency key for this action. Never reuse for a different operation. [max 64 chars]"),
            ),
            optional = linkedMapOf(
                "amount" to ParamSpec(ParamType.INTEGER, "Amount 1?20 (default 3) [1–20]"),
                "x" to ParamSpec(ParamType.INTEGER, "Normalized X [0–999]"),
                "y" to ParamSpec(ParamType.INTEGER, "Normalized Y [0–999]"),
                "approval_token" to ParamSpec(ParamType.STRING, "Single-use chat approval token (GER PR-6) minted by phone_approval_issue for exactly this tool + arguments. Authorizes that one gated action once; never authorizes payment. [max 512 chars]"),
            ),
            timeoutMs = 30_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.swipe",
            description = "Swipe using 0?999 normalized endpoints.",
            required = linkedMapOf(
                "request_id" to ParamSpec(ParamType.STRING, "Unique UUID idempotency key for this action. Never reuse for a different operation. [max 64 chars]"),
            ),
            optional = linkedMapOf(
                "startX" to ParamSpec(ParamType.INTEGER, "Start X [0–999]"),
                "startY" to ParamSpec(ParamType.INTEGER, "Start Y [0–999]"),
                "endX" to ParamSpec(ParamType.INTEGER, "End X [0–999]"),
                "endY" to ParamSpec(ParamType.INTEGER, "End Y [0–999]"),
                "durationMs" to ParamSpec(ParamType.INTEGER, "Gesture duration ms [50–5000]"),
                "approval_token" to ParamSpec(ParamType.STRING, "Single-use chat approval token (GER PR-6) minted by phone_approval_issue for exactly this tool + arguments. Authorizes that one gated action once; never authorizes payment. [max 512 chars]"),
            ),
            timeoutMs = 30_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.gesture",
            description = "Perform a preplanned multipoint gesture with existing phone_gesture safety gates.",
            required = linkedMapOf(
                "paths" to ParamSpec(ParamType.ARRAY, "Gesture paths: each path is a list of [x, y] points, normalized 0–999. [1–2 items of array (2–32 items of array (2–2 items of integer (0–999)))]"),
                "request_id" to ParamSpec(ParamType.STRING, "Unique UUID idempotency key for this action. Never reuse for a different operation. [max 64 chars]"),
            ),
            optional = linkedMapOf(
                "durationMs" to ParamSpec(ParamType.INTEGER, "Gesture duration ms [50–5000]"),
                "approval_token" to ParamSpec(ParamType.STRING, "Single-use chat approval token (GER PR-6) minted by phone_approval_issue for exactly this tool + arguments. Authorizes that one gated action once; never authorizes payment. [max 512 chars]"),
            ),
            timeoutMs = 30_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.press_key",
            description = "Press a key allowed by the agent virtual-display key policy. HOME, Recents, volume and media keys are scoped to this virtual display; Power remains blocked.",
            required = linkedMapOf(
                "key" to ParamSpec(ParamType.STRING, "Allowed KEYCODE_* name for the virtual session"),
                "request_id" to ParamSpec(ParamType.STRING, "Unique UUID idempotency key for this action. Never reuse for a different operation. [max 64 chars]"),
            ),
            optional = linkedMapOf(
                "approval_token" to ParamSpec(ParamType.STRING, "Single-use chat approval token (GER PR-6) minted by phone_approval_issue for exactly this tool + arguments. Authorizes that one gated action once; never authorizes payment. [max 512 chars]"),
            ),
            timeoutMs = 30_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.volume",
            description = "Adjust virtual-session volume by one scoped volume step.",
            required = linkedMapOf(
                "direction" to ParamSpec(ParamType.STRING, "up, down or mute"),
                "request_id" to ParamSpec(ParamType.STRING, "Unique UUID idempotency key for this action. Never reuse for a different operation. [max 64 chars]"),
            ),
            optional = linkedMapOf(
                "approval_token" to ParamSpec(ParamType.STRING, "Single-use chat approval token (GER PR-6) minted by phone_approval_issue for exactly this tool + arguments. Authorizes that one gated action once; never authorizes payment. [max 512 chars]"),
            ),
            timeoutMs = 30_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.media",
            description = "Send a scoped media control key to the virtual session.",
            required = linkedMapOf(
                "action" to ParamSpec(ParamType.STRING, "play_pause, stop, next or previous"),
                "request_id" to ParamSpec(ParamType.STRING, "Unique UUID idempotency key for this action. Never reuse for a different operation. [max 64 chars]"),
            ),
            optional = linkedMapOf(
                "approval_token" to ParamSpec(ParamType.STRING, "Single-use chat approval token (GER PR-6) minted by phone_approval_issue for exactly this tool + arguments. Authorizes that one gated action once; never authorizes payment. [max 512 chars]"),
            ),
            timeoutMs = 30_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.press_back",
            description = "Go back in the virtual display.",
            required = linkedMapOf(
                "request_id" to ParamSpec(ParamType.STRING, "Unique UUID idempotency key for this action. Never reuse for a different operation. [max 64 chars]"),
            ),
            optional = linkedMapOf(
                "approval_token" to ParamSpec(ParamType.STRING, "Single-use chat approval token (GER PR-6) minted by phone_approval_issue for exactly this tool + arguments. Authorizes that one gated action once; never authorizes payment. [max 512 chars]"),
            ),
            timeoutMs = 30_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.run_batch",
            description = "Run an ordered batch of at most 30 predefined atomic actions, verified individually. No autonomous decisions. A step's arguments may carry its own approval_token.",
            required = linkedMapOf(
                "commands" to ParamSpec(ParamType.ARRAY, "Array of {name, arguments} (without individual request_id). [up to 30 items of object]"),
                "request_id" to ParamSpec(ParamType.STRING, "Unique UUID idempotency key for this action. Never reuse for a different operation. [max 64 chars]"),
            ),
            optional = emptyMap(),
            timeoutMs = 300_000L,
            writes = true,
        ),
        CommandSpec.stub(
            name = "phone.cancel",
            description = "End the owner's control session and release its virtual display.",
            required = emptyMap(),
            optional = emptyMap(),
            timeoutMs = 10_000L,
            writes = true,
        ),
    )
}
