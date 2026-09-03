import { ApiHttp, seg } from './http';

/**
 * The four routes a dashboard-owned library answers: list, create, update, delete.
 *
 * Eight clients under this folder had these written out over their own path — the same twenty
 * lines, differing in a string and two type arguments. A client that needs more (a deploy, a
 * capture, an upstream check) extends this and adds it; nothing is generated and every extra
 * route is still written where it belongs.
 *
 * Only for the libraries whose four routes are exactly this. `McpCatalogApi` stays as it is:
 * its delete answers the entry rather than nothing, and a client that has to lie about its
 * own contract to fit a base class is the wrong client for one.
 */
export class CrudApi<T, I> {
  /** Protected so a subclass can compose its own routes off the same base. */
  constructor(protected readonly http: ApiHttp, private readonly path: string) {}

  list(): Promise<T[]> {
    return this.http.get(this.path);
  }

  create(input: I): Promise<T> {
    return this.http.post(this.path, input);
  }

  update(id: string, input: I): Promise<T> {
    return this.http.put(`${this.path}/${seg(id)}`, input);
  }

  remove(id: string): Promise<void> {
    return this.http.delete(`${this.path}/${seg(id)}`);
  }
}
