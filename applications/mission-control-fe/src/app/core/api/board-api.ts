import { BoardColumn } from '../models';
import { ApiBoardTask } from './api-types';
import { ApiHttp } from './http';

/** `/api/board` — the kanban tasks shared across containers. */
export class BoardApi {
  constructor(private readonly http: ApiHttp) {}

  tasks(): Promise<ApiBoardTask[]> {
    return this.http.get('/api/board/tasks');
  }

  moveTask(id: string, column: BoardColumn): Promise<void> {
    return this.http.patch(`/api/board/tasks/${id}`, { column });
  }
}
