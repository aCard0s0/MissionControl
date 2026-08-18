import { describe, expect, it } from 'vitest';
import { quoteMcpArg, quoteMcpArgs } from './mcp-args';

// An MCP server's arguments are a list. Flattening one into a string is only
// reversible if anything a splitter would break on is quoted first.
describe('quoteMcpArg', () => {
  it('leaves an ordinary argument alone', () => {
    expect(quoteMcpArg('--port')).toBe('--port');
    expect(quoteMcpArg('@acme/server')).toBe('@acme/server');
  });

  it('quotes an argument carrying whitespace, so it stays one argument', () => {
    expect(quoteMcpArg('hello world')).toBe("'hello world'");
    expect(quoteMcpArg('--message=hello world')).toBe("'--message=hello world'");
  });

  it('quotes an empty argument, which would otherwise vanish', () => {
    expect(quoteMcpArg('')).toBe("''");
  });

  it('escapes an embedded single quote the way a shell requires', () => {
    expect(quoteMcpArg("it's")).toBe(`'it'"'"'s'`);
  });

  it('quotes double quotes too, rather than trusting them through', () => {
    expect(quoteMcpArg('say "hi"')).toBe(`'say "hi"'`);
  });
});

describe('quoteMcpArgs', () => {
  it('joins a list into one round-trip-safe string', () => {
    expect(quoteMcpArgs(['-y', '@acme/server', 'two words']))
      .toBe("-y @acme/server 'two words'");
  });

  it('answers undefined for no arguments, so the field is omitted', () => {
    expect(quoteMcpArgs([])).toBeUndefined();
  });
});
