import { ApiCronJobs, ApiCronJobRequest } from './api-types';
import { AgentRef, agentPath } from './agent-ref';
import { ApiHttp, seg } from './http';

/**
 * `/api/agents/**\/cron` — a profile's scheduled jobs.
 *
 * Every call answers with the whole schedule as hermes now holds it, because hermes
 * owns the parts the dashboard cannot compute: the job id, the parsed schedule and
 * the next run time.
 */
export class AgentCronApi {
  constructor(private readonly http: ApiHttp) {}

  list(ref: AgentRef): Promise<ApiCronJobs> {
    return this.http.get(this.path(ref));
  }

  create(ref: AgentRef, request: ApiCronJobRequest): Promise<ApiCronJobs> {
    return this.http.post(this.path(ref), request);
  }

  update(ref: AgentRef, jobId: string, request: ApiCronJobRequest): Promise<ApiCronJobs> {
    return this.http.patch(this.job(ref, jobId), request);
  }

  setEnabled(ref: AgentRef, jobId: string, enabled: boolean): Promise<ApiCronJobs> {
    return this.http.post(`${this.job(ref, jobId)}/${enabled ? 'resume' : 'pause'}`);
  }

  /** Asks for the job on the next scheduler tick rather than at its schedule. */
  runNow(ref: AgentRef, jobId: string): Promise<ApiCronJobs> {
    return this.http.post(`${this.job(ref, jobId)}/run`);
  }

  remove(ref: AgentRef, jobId: string): Promise<ApiCronJobs> {
    return this.http.delete(this.job(ref, jobId));
  }

  private path(ref: AgentRef): string {
    return `${agentPath(ref)}/cron`;
  }

  private job(ref: AgentRef, jobId: string): string {
    return `${this.path(ref)}/${seg(jobId)}`;
  }
}
