// Cloudflare Worker environment type bindings
declare namespace Cloudflare {
  interface Env {
    ASSETS?: Fetcher;
    DB?: D1Database;
  }
}
