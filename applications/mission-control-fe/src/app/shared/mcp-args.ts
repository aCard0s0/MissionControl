/**
 * Argument lists travel to an Agent's `config.yaml` as one string, but they are
 * never shell-parsed on the way back: `--message=hello world` is one argument,
 * not two. Quoting here is what keeps a list round-trip safe, and it mirrors the
 * backend's tokenizer so the pre-save preview matches what gets materialized.
 */
export function quoteMcpArg(value: string): string {
  if (value && !/[\s'"]/.test(value)) return value;
  return `'${value.replaceAll("'", "'\"'\"'")}'`;
}

/** The flat `args` string for a list of arguments, or undefined when empty. */
export function quoteMcpArgs(args: readonly string[]): string | undefined {
  return args.length ? args.map(quoteMcpArg).join(' ') : undefined;
}
