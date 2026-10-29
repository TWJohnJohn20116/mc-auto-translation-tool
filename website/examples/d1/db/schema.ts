import { sql } from "drizzle-orm";
import { index, integer, sqliteTable, text } from "drizzle-orm/sqlite-core";

export const notes = sqliteTable(
  "notes",
  {
    id: integer("id").primaryKey({ autoIncrement: true }),
    title: text("title").notNull(),
    content: text("content").notNull().default(""),
    createdAt: text("created_at").notNull().default(sql`CURRENT_TIMESTAMP`),
  },
  // `GET` orders by `(created_at desc, id desc)` and takes 20 rows; without this
  // index SQLite scans the whole table and sorts it on every request.
  (table) => [index("notes_created_at_idx").on(table.createdAt, table.id)],
);
