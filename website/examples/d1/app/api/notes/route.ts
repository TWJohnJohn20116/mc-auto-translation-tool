import { desc } from "drizzle-orm";
import { D1_BINDING_MISSING, getDb } from "../../../../../db";
import { notes } from "../../../db/schema";

const MAX_TITLE_LENGTH = 200;
const MAX_CONTENT_LENGTH = 10_000;

// The database-unavailable hint is safe to show because it only reveals that the
// schema has not been migrated yet. Everything else stays server-side: these
// handlers are reachable without authentication, and raw D1/SQL errors expose
// table, column, and binding details.
const NOTES_TABLE_UNAVAILABLE =
  "The notes table is unavailable. Generate the migration locally with `npm run db:generate`, then deploy so the platform can apply the generated SQL to the real D1 database.";
const GENERIC_SERVER_ERROR = "Internal server error";

function isBindingMissing(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    (error as { code?: unknown }).code === D1_BINDING_MISSING
  );
}

function toRouteError(error: unknown): { message: string; status: number } {
  // A missing binding carries the one actionable instruction the operator needs
  // (`hosting.json` must set `d1` to `DB`) and no query text, so surface it.
  if (isBindingMissing(error)) {
    console.error("[notes] database binding is missing:", error);
    return {
      message:
        error instanceof Error ? error.message : GENERIC_SERVER_ERROR,
      status: 503,
    };
  }

  const message = error instanceof Error ? error.message : "Unexpected error";
  const detail =
    error instanceof Error && error.cause instanceof Error ? error.cause.message : "";
  const combined = `${message}\n${detail}`;

  // D1 names the missing table (`no such table: notes`). Matching the rendered SQL
  // (`from "notes"`) instead also catches constraint and parameter errors, which
  // would report a failed write as "the schema was never deployed".
  if (combined.includes("no such table: notes")) {
    return { message: NOTES_TABLE_UNAVAILABLE, status: 500 };
  }

  // Log the underlying failure (including the cause chain) for operators while
  // returning a generic message to the caller.
  console.error("[notes] request failed:", error);
  return { message: GENERIC_SERVER_ERROR, status: 500 };
}

function parseNoteField(
  value: unknown,
  field: "title" | "content",
  maxLength: number,
): { ok: true; value: string } | { ok: false; error: string } {
  if (value === undefined || value === null) return { ok: true, value: "" };
  if (typeof value !== "string") {
    return { ok: false, error: `${field} must be a string` };
  }
  const trimmed = value.trim();
  if (trimmed.length > maxLength) {
    return { ok: false, error: `${field} must be at most ${maxLength} characters` };
  }
  return { ok: true, value: trimmed };
}

export async function GET() {
  try {
    const db = getDb();
    const rows = await db
      .select()
      .from(notes)
      .orderBy(desc(notes.createdAt), desc(notes.id))
      .limit(20);

    return Response.json({ notes: rows });
  } catch (error) {
    const { message, status } = toRouteError(error);
    return Response.json({ error: message }, { status });
  }
}

export async function POST(request: Request) {
  try {
    let payload: unknown;
    try {
      payload = await request.json();
    } catch {
      return Response.json({ error: "request body must be valid JSON" }, { status: 400 });
    }
    if (typeof payload !== "object" || payload === null || Array.isArray(payload)) {
      return Response.json({ error: "request body must be a JSON object" }, { status: 400 });
    }

    const body = payload as { title?: unknown; content?: unknown };
    const parsedTitle = parseNoteField(body.title, "title", MAX_TITLE_LENGTH);
    if (!parsedTitle.ok) {
      return Response.json({ error: parsedTitle.error }, { status: 400 });
    }
    const parsedContent = parseNoteField(body.content, "content", MAX_CONTENT_LENGTH);
    if (!parsedContent.ok) {
      return Response.json({ error: parsedContent.error }, { status: 400 });
    }

    const title = parsedTitle.value;
    const content = parsedContent.value;

    if (!title) {
      return Response.json({ error: "title is required" }, { status: 400 });
    }

    const db = getDb();
    const [note] = await db.insert(notes).values({ title, content }).returning();
    return Response.json({ note }, { status: 201 });
  } catch (error) {
    const { message, status } = toRouteError(error);
    return Response.json({ error: message }, { status });
  }
}
