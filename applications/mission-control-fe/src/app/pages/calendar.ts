import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AgentStore } from '../core/store/agent-store';
import { ContainerStore } from '../core/store/container-store';
import { JobStore } from '../core/store/job-store';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { ago, dayStamp, monthStamp, until } from '../core/format';
import { SCHEDULE_PRESETS, describeSchedule } from '../core/cron';
import { CronJob } from '../core/models';

/** The 1st of the month `d` falls in, at local midnight. */
const firstOfMonth = (d: Date): Date => new Date(d.getFullYear(), d.getMonth(), 1);

/** Two dates that fall on the same local calendar day. */
const sameDay = (a: Date, b: Date): boolean => a.toDateString() === b.toDateString();

/** Days into the week, counting from Monday — `getDay()` counts from Sunday. */
const fromMonday = (d: Date): number => (d.getDay() + 6) % 7;

/**
 * Every day the month's grid shows: the whole of `month`, padded out to full Monday-start
 * weeks at both ends.
 *
 * <p>Built by day-of-month arithmetic rather than by adding milliseconds, so the two days a
 * year that are not 24 hours long land on the square they belong to.
 */
function monthGrid(month: Date): Date[] {
  const year = month.getFullYear();
  const index = month.getMonth();
  const first = new Date(year, index, 1 - fromMonday(new Date(year, index, 1)));
  const lastOfMonth = new Date(year, index + 1, 0);
  const last = new Date(year, index, lastOfMonth.getDate() + (6 - fromMonday(lastOfMonth)));
  const days: Date[] = [];
  for (let d = first; d <= last; d = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1)) {
    days.push(d);
  }
  return days;
}

@Component({
  selector: 'mc-calendar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, StatusDot, Reveal],
  templateUrl: './calendar.html',
  styleUrl: './calendar.scss',
})
export class CalendarPage {
  protected readonly agents = inject(AgentStore);
  protected readonly containers = inject(ContainerStore);
  protected readonly jobs = inject(JobStore);

  protected readonly ago = ago;
  protected readonly until = until;

  protected readonly month = signal(firstOfMonth(new Date()));
  protected readonly selectedDay = signal<Date | null>(null);
  protected readonly editing = signal<CronJob | null>(null);
  protected readonly creating = signal(false);

  // edit/create form fields
  protected fName = '';
  protected fSchedule = '';
  protected fPrompt = '';
  protected fDeliver = '';
  protected fAgent = '';

  protected readonly presets = SCHEDULE_PRESETS;
  protected readonly saving = signal(false);
  protected scheduleHelp = () => describeSchedule(this.fSchedule);

  protected readonly weekdays = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

  protected readonly monthLabel = computed(() => monthStamp(this.month()));

  protected readonly days = computed(() => {
    const m = this.month();
    const today = new Date();
    return monthGrid(m).map(d => ({
      date: d,
      inMonth: d.getMonth() === m.getMonth() && d.getFullYear() === m.getFullYear(),
      today: sameDay(d, today),
      jobs: this.jobs.forSelectedContainer().filter(j => sameDay(new Date(j.nextRun), d)),
    }));
  });

  protected readonly dayJobs = computed(() => {
    const d = this.selectedDay();
    const js = this.jobs.forSelectedContainer();
    if (!d) return js.slice().sort((a, b) => a.nextRun - b.nextRun);
    return js.filter(j => sameDay(new Date(j.nextRun), d));
  });

  protected readonly dayLabel = computed(() => {
    const d = this.selectedDay();
    return d ? dayStamp(d) : 'ALL SCHEDULED JOBS';
  });

  protected shiftMonth(n: number): void {
    this.month.update(m => new Date(m.getFullYear(), m.getMonth() + n, 1));
    this.selectedDay.set(null);
  }

  protected pickDay(d: Date): void {
    this.selectedDay.update(cur => cur && sameDay(cur, d) ? null : d);
    this.editing.set(null);
  }

  protected agentName(id: string): string {
    return this.agents.byId(id)?.name ?? '?';
  }

  protected startEdit(j: CronJob): void {
    this.creating.set(false);
    this.editing.set(j);
    this.fName = j.name;
    this.fSchedule = j.schedule;
    this.fPrompt = j.prompt;
    this.fDeliver = j.deliverTo;
    this.fAgent = j.agentId;
  }

  protected startCreate(): void {
    this.editing.set(null);
    this.creating.set(true);
    this.fName = this.fSchedule = this.fPrompt = this.fDeliver = '';
    this.fAgent = this.agents.forSelectedContainer()[0]?.id ?? '';
  }

  /**
   * Hermes parses the schedule, mints the id and decides the next run, so the form stays
   * open until the write comes back — closing it early would leave the operator looking at
   * a schedule that does not contain what they just typed.
   */
  protected async save(): Promise<void> {
    const container = this.containers.selected();
    if (!this.fName.trim() || !this.scheduleHelp().valid || !this.fAgent || this.saving()) return;
    const job = this.editing();
    this.saving.set(true);
    const saved = job
      ? await this.jobs.update(job.id, {
          name: this.fName, schedule: this.fSchedule, prompt: this.fPrompt,
          deliverTo: this.fDeliver,
        })
      : !!container && await this.jobs.create(
          container.id, this.fAgent, this.fName, this.fSchedule, this.fPrompt,
          this.fDeliver || 'local');
    this.saving.set(false);
    if (!saved) return;   // the store has already said why
    this.editing.set(null);
    this.creating.set(false);
  }

  /** Runs the job on the next scheduler tick instead of waiting for its schedule. */
  protected runNow(job: CronJob): void {
    void this.jobs.runNow(job.id);
  }

  protected cancelForm(): void {
    this.editing.set(null);
    this.creating.set(false);
  }
}
