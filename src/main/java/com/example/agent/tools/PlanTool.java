package com.example.agent.tools;

import com.example.agent.model.PlanItem;
import com.example.agent.model.Session;
import com.example.agent.model.ToolResult;
import com.example.agent.service.AuditLogger;
import com.example.agent.service.SessionStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code plan} (#174): the agent's to-do list for a multi-step task, kept on the session so a
 * continued session (after a budget stop, say) starts from it. {@code set} replaces the list,
 * {@code update} moves one item to {@code pending}, {@code in_progress} or {@code done} with an
 * optional note, {@code read} shows it. One item is {@code in_progress} at a time. Every change
 * is saved with the session and audited as {@code plan.updated}.
 */
@Component
public class PlanTool implements Tool {

    static final int MAX_ITEMS = 20;
    static final int MAX_TEXT_CHARS = 200;

    private final SessionStore store;
    private AuditLogger auditLogger;

    public PlanTool(SessionStore store) {
        this.store = store;
    }

    @Autowired(required = false)
    public void setAuditLogger(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    @Override public String name() { return "plan"; }

    @Override public String description() {
        return "Keep a short plan for a multi-step task. operation=set with items (a list of short steps) replaces it; "
                + "operation=update with item (its number) and status (pending, in_progress or done) and an optional note "
                + "marks progress; operation=read shows it. Keep one step in_progress. The plan is kept with the session "
                + "and shown again when the session continues.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "operation", Map.of("type", "string", "enum", List.of("set", "update", "read")),
                        "items", Map.of("type", "array", "items", Map.of("type", "string"), "description", "For set: the steps, in order."),
                        "item", Map.of("type", "integer", "description", "For update: the step's number, from 1."),
                        "status", Map.of("type", "string", "enum", List.of(PlanItem.PENDING, PlanItem.IN_PROGRESS, PlanItem.DONE)),
                        "note", Map.of("type", "string", "description", "For update: optional, one line.")),
                "required", List.of("operation"));
    }

    @Override
    public ToolResult execute(String callId, Map<String, Object> arguments, ToolContext context) {
        Map<String, Object> args = arguments;
        Optional<Session> found = context.session();
        if (args == null) args = Map.of();
        if (found.isEmpty()) {
            return ToolResult.error(callId, "plan is only available inside a running session.");
        }
        Session session = found.get();
        String op = String.valueOf(args.get("operation")).toLowerCase(Locale.ROOT);
        switch (op) {
            case "read":
                return ToolResult.ok(callId, render(session.getPlan()));
            case "set": {
                Object raw = args.get("items");
                if (!(raw instanceof List<?> list) || list.isEmpty()) {
                    return ToolResult.error(callId, "plan set needs items: a non-empty list of short steps.");
                }
                if (list.size() > MAX_ITEMS) {
                    return ToolResult.error(callId, "plan set takes at most " + MAX_ITEMS + " steps; merge some.");
                }
                List<PlanItem> items = new ArrayList<>();
                for (Object o : list) {
                    String text = clip(String.valueOf(o).strip());
                    if (text.isEmpty()) return ToolResult.error(callId, "plan set: a step is empty.");
                    items.add(new PlanItem(text, PlanItem.PENDING, null));
                }
                return saved(callId, context, session, items, "set");
            }
            case "update": {
                List<PlanItem> items = new ArrayList<>(session.getPlan());
                if (items.isEmpty()) return ToolResult.error(callId, "There is no plan yet; use operation=set first.");
                int n;
                try {
                    n = Integer.parseInt(String.valueOf(args.get("item")).replaceAll("\\.0+$", ""));
                } catch (NumberFormatException e) {
                    return ToolResult.error(callId, "plan update needs item: the step's number, from 1 to " + items.size() + ".");
                }
                if (n < 1 || n > items.size()) {
                    return ToolResult.error(callId, "No step " + n + "; the plan has " + items.size() + ".");
                }
                String status = String.valueOf(args.get("status")).toLowerCase(Locale.ROOT);
                if (!List.of(PlanItem.PENDING, PlanItem.IN_PROGRESS, PlanItem.DONE).contains(status)) {
                    return ToolResult.error(callId, "status must be pending, in_progress or done.");
                }
                Object note = args.get("note");
                PlanItem old = items.get(n - 1);
                if (PlanItem.IN_PROGRESS.equals(status)) {
                    // One step in progress at a time: any other goes back to pending.
                    for (int i = 0; i < items.size(); i++) {
                        PlanItem it = items.get(i);
                        if (i != n - 1 && PlanItem.IN_PROGRESS.equals(it.status())) {
                            items.set(i, new PlanItem(it.text(), PlanItem.PENDING, it.note()));
                        }
                    }
                }
                items.set(n - 1, new PlanItem(old.text(), status, note == null ? old.note() : clip(String.valueOf(note).strip())));
                return saved(callId, context, session, items, "update");
            }
            default:
                return ToolResult.error(callId, "operation must be set, update or read.");
        }
    }

    private ToolResult saved(String callId, ToolContext context, Session session, List<PlanItem> items, String op) {
        session.setPlan(items);
        store.update(session);
        if (auditLogger != null) {
            long done = items.stream().filter(i -> PlanItem.DONE.equals(i.status())).count();
            auditLogger.planUpdated(context.userId(), session.getId(), op, items.size(), (int) done);
        }
        return ToolResult.ok(callId, render(items));
    }

    /** The plan as numbered lines, for the model and for the system prompt of a continued session. */
    public static String render(List<PlanItem> items) {
        if (items.isEmpty()) return "No plan yet.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            PlanItem it = items.get(i);
            sb.append(i + 1).append(". [").append(it.status()).append("] ").append(it.text());
            if (it.note() != null) sb.append(" (").append(it.note()).append(')');
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    /**
     * One line, at most {@link #MAX_TEXT_CHARS}: newlines, tabs and control characters become single
     * spaces, so a step can never start a heading or a new block in the system prompt it is shown in.
     */
    static String clip(String s) {
        // (?U): Unicode whitespace too, so U+2028/U+2029/U+0085 line separators are flattened as well.
        String flat = s.replaceAll("(?U)[\\p{Cntrl}\\p{Z}\\s]+", " ").strip();
        return flat.length() <= MAX_TEXT_CHARS ? flat : flat.substring(0, MAX_TEXT_CHARS) + "…";
    }
}
