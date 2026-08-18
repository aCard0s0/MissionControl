import { McpTransport } from '../core/models';

/** Transport-specific endpoint fields, as the store and the template editor
 *  both want them: a url for http/sse, a command plus args for stdio. */
export interface McpEndpointOptions {
  url?: string;
  command?: string;
  args?: string;
}

/**
 * The "add an MCP server" form, which the agent detail page and the profile
 * template editor both put on screen with their own layout. What they must agree
 * on is the rule — a name, plus a command for stdio or a url otherwise — so the
 * fields and that rule live here rather than in each page.
 *
 * Plain mutable fields, because both templates bind them with `[(ngModel)]`.
 */
export class McpEndpointForm {
  name = '';
  transport: McpTransport;
  url = '';
  command = '';
  args = '';

  constructor(private readonly defaultTransport: McpTransport = 'http') {
    this.transport = defaultTransport;
  }

  get stdio(): boolean {
    return this.transport === 'stdio';
  }

  /** True once the submit button should be enabled. */
  valid(): boolean {
    if (!this.name.trim()) return false;
    return this.stdio ? !!this.command.trim() : !!this.url.trim();
  }

  /** The trimmed endpoint for the active transport, or null when incomplete. */
  endpoint(): McpEndpointOptions | null {
    if (!this.valid()) return null;
    return this.stdio
      ? { command: this.command.trim(), args: this.args.trim() || undefined }
      : { url: this.url.trim() };
  }

  trimmedName(): string {
    return this.name.trim();
  }

  /** Loads an existing server in for editing. */
  load(server: { name: string; transport: McpTransport; url?: string; command?: string; args?: string }): void {
    this.name = server.name;
    this.transport = server.transport;
    this.url = server.url ?? '';
    this.command = server.command ?? '';
    this.args = server.args ?? '';
  }

  reset(): void {
    this.name = '';
    this.transport = this.defaultTransport;
    this.url = '';
    this.command = '';
    this.args = '';
  }
}
