import { ApiLogLine } from './api-types';
import { ApiHttp } from './http';

/** What the dashboard's own process reports about itself. */
export interface ApiServerInfo {
  version: string;
  /** how many lines the server's in-memory ring holds before the oldest fall out */
  retained: number;
  startedAt: number;
}

/**
 * `/api/server` — Mission Control's own logs, in the same {@link ApiLogLine} shape a
 * container tail returns so both render through one component.
 */
export class ServerApi {
  constructor(private readonly http: ApiHttp) {}

  /** @param level error|warn|info|debug, or omitted for everything retained */
  logs(tail = 200, level?: string): Promise<ApiLogLine[]> {
    const query = level && level !== 'all' ? `&level=${encodeURIComponent(level)}` : '';
    return this.http.get(`/api/server/logs?tail=${tail}${query}`);
  }

  info(): Promise<ApiServerInfo> {
    return this.http.get('/api/server/info');
  }
}
