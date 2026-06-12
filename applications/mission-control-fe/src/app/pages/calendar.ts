import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  addMonths, eachDayOfInterval, endOfMonth, endOfWeek, format, isSameDay, isSameMonth,
  isToday, startOfMonth, startOfWeek,
} from 'date-fns';
import { HermesStore } from '../core/hermes-store';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { ago, until } from '../core/format';
import { SCHEDULE_PRESETS, describeSchedule } from '../core/cron';
import { CronJob } from '../core/models';

@Component({
  selector: 'mc-calendar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, StatusDot, Reveal],
  templateUrl: './calendar.html',
  styleUrl: './calendar.scss',
})
export class CalendarPage {
  protected readonly store = inject(HermesStore);

  protected readonly ago = ago;
  protected readonly until = until;

  protected readonly month = signal(startOfMonth(new Date()));
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
  protected scheduleHelp = () => describeSchedule(this.fSchedule);

  protected readonly weekdays = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

  protected readonly monthLabel = computed(() => format(this.month(), 'MMMM yyyy').toUpperCase());

  protected readonly days = computed(() => {
    const m = this.month();
    return eachDayOfInterval({
      start: startOfWeek(startOfMonth(m), { weekStartsOn: 1 }),
      end: endOfWeek(endOfMonth(m), { weekStartsOn: 1 }),
    }).map(d => ({
      date: d,
      inMonth: isSameMonth(d, m),
      today: isToday(d),
      jobs: this.store.containerJobs().filter(j => isSameDay(new Date(j.nextRun), d)),
    }));
  });

  protected readonly dayJobs = computed(() => {
    const d = this.selectedDay();
    const js = this.store.containerJobs();
    if (!d) return js.slice().sort((a, b) => a.nextRun - b.nextRun);
    return js.filter(j => isSameDay(new Date(j.nextRun), d));
  });

  protected readonly dayLabel = computed(() => {
    const d = this.selectedDay();
    return d ? format(d, 'EEE dd MMM').toUpperCase() : 'ALL SCHEDULED JOBS';
  });

  protected shiftMonth(n: number): void {
    this.month.update(m => addMonths(m, n));
    this.selectedDay.set(null);
  }

  protected pickDay(d: Date): void {
    this.selectedDay.update(cur => cur && isSameDay(cur, d) ? null : d);
    this.editing.set(null);
  }

  protected agentName(id: string): string {
    return this.store.agentById(id)?.name ?? '?';
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
    this.fAgent = this.store.containerAgents()[0]?.id ?? '';
  }

  protected save(): void {
    const container = this.store.selectedContainer();
    if (!this.fName.trim() || !this.scheduleHelp().valid || !this.fAgent) return;
    const j = this.editing();
    if (j) {
      this.store.updateJob(j.id, {
        name: this.fName, schedule: this.fSchedule, prompt: this.fPrompt,
        deliverTo: this.fDeliver, agentId: this.fAgent,
      });
      this.editing.set(null);
    } else if (container) {
      this.store.createJob(container.id, this.fAgent, this.fName, this.fSchedule, this.fPrompt, this.fDeliver || 'cli');
      this.creating.set(false);
    }
  }

  protected cancelForm(): void {
    this.editing.set(null);
    this.creating.set(false);
  }
}
