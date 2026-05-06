import { Capacitor, registerPlugin } from '@capacitor/core';

const PlannerWidget = registerPlugin('PlannerWidget');

export const COLUMNS = [
  { id: 'study', label: '공부', icon: '📘', cls: 'col-study', color: '#2d6fce' },
  { id: 'club', label: '동아리', icon: '🎯', cls: 'col-club', color: '#d4960a' },
  { id: 'money', label: '돈벌이', icon: '💰', cls: 'col-money', color: '#2e9e5b' },
  { id: 'etc', label: '기타', icon: '🧩', cls: 'col-etc', color: '#8b52cc' },
];

export const DATE_TYPES = {
  RANGE: 'range',
  STAY: 'stay',
  RECURRING: 'recurring',
  SPECIFIC: 'specific',
};

export const DEFAULT_VIEWPORT_START_MINUTE = 8 * 60;
export const DEFAULT_VIEWPORT_END_MINUTE = 24 * 60;

function isAndroidNative() {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';
}

function addDays(baseDate, amount) {
  const nextDate = new Date(baseDate);
  nextDate.setDate(nextDate.getDate() + amount);
  return nextDate;
}

function getWeekStart(baseDate) {
  const weekStart = new Date(baseDate);
  const dayOffset = (weekStart.getDay() + 6) % 7;
  weekStart.setHours(0, 0, 0, 0);
  weekStart.setDate(weekStart.getDate() - dayOffset);
  return weekStart;
}

function formatDate(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function parseTimeToMinutes(time) {
  if (!time || !time.includes(':')) return 0;
  const [hours, minutes] = time.split(':').map(Number);
  return (hours || 0) * 60 + (minutes || 0);
}

function getTaskInDate(task, dateStr, dayOfWeek) {
  if (task.dateType === DATE_TYPES.RECURRING) {
    if (task.excludeDates?.includes(dateStr)) return false;
    return Boolean(task.days?.includes(dayOfWeek));
  }

  if (task.dateType === DATE_TYPES.SPECIFIC) {
    return Boolean(task.dates?.includes(dateStr));
  }

  if (task.start && task.end) {
    return dateStr >= task.start && dateStr <= task.end;
  }

  return false;
}

function normalizeManualItem(item) {
  const startMinute = parseTimeToMinutes(item?.start);
  const endMinute = parseTimeToMinutes(item?.end);
  if (endMinute <= startMinute) return null;

  return {
    id: item?.id ?? null,
    title: item?.title || 'Untitled',
    startMinute,
    endMinute,
    source: 'manual',
  };
}

function normalizeAutoTask(task) {
  const startMinute = parseTimeToMinutes(task?.fixedStart);
  const endMinute = task?.fixedEnd ? parseTimeToMinutes(task.fixedEnd) : startMinute + 60;
  if (!task?.autoSched || !task?.fixedStart || endMinute <= startMinute) return null;

  return {
    id: task?.id ?? null,
    title: task?.title || 'Untitled',
    startMinute,
    endMinute,
    source: 'auto',
    dateType: task?.dateType || DATE_TYPES.RANGE,
    start: task?.start || '',
    end: task?.end || '',
    days: Array.isArray(task?.days) ? task.days : [],
  };
}

function sortItems(a, b) {
  if (a.startMinute !== b.startMinute) return a.startMinute - b.startMinute;
  if (a.endMinute !== b.endMinute) return a.endMinute - b.endMinute;
  return String(a.title).localeCompare(String(b.title));
}

function buildManualEventsByDate(db) {
  return Object.fromEntries(
    Object.entries(db?.dailySchedules || {}).map(([dateStr, items]) => [
      dateStr,
      (Array.isArray(items) ? items : [])
        .map(normalizeManualItem)
        .filter(Boolean)
        .sort(sortItems),
    ]),
  );
}

function buildAutoPlans(db) {
  return COLUMNS.flatMap((column) => (Array.isArray(db?.[column.id]) ? db[column.id] : []))
    .map(normalizeAutoTask)
    .filter(Boolean);
}

function buildWeekDays({ manualEventsByDate, autoPlans, weekStart }) {
  return Array.from({ length: 7 }, (_, index) => {
    const date = addDays(weekStart, index);
    const dateStr = formatDate(date);
    const dayOfWeek = date.getDay();
    const manualItems = Array.isArray(manualEventsByDate[dateStr]) ? manualEventsByDate[dateStr] : [];
    const autoItems = autoPlans
      .filter((task) => getTaskInDate(task, dateStr, dayOfWeek))
      .map(({ dateType, start, end, days, ...item }) => item);

    return {
      date: dateStr,
      items: [...manualItems, ...autoItems].sort(sortItems),
    };
  });
}

export function buildSummaryWidgetSnapshot({ db }) {
  const weekStart = getWeekStart(new Date());
  const manualEventsByDate = buildManualEventsByDate(db);
  const autoPlans = buildAutoPlans(db);

  return {
    generatedAt: new Date().toISOString(),
    weekStart: formatDate(weekStart),
    weekOffset: 0,
    viewportStartMinute: DEFAULT_VIEWPORT_START_MINUTE,
    viewportEndMinute: DEFAULT_VIEWPORT_END_MINUTE,
    manualEventsByDate,
    autoPlans,
    days: buildWeekDays({ manualEventsByDate, autoPlans, weekStart }),
  };
}

export async function syncSummaryWidgetSnapshot(payload) {
  if (!isAndroidNative()) return false;

  try {
    const snapshot = buildSummaryWidgetSnapshot(payload);
    await PlannerWidget.saveSummarySnapshot({
      snapshotJson: JSON.stringify(snapshot),
    });
    return true;
  } catch (error) {
    if (import.meta.env.DEV) {
      console.warn('Failed to sync summary widget snapshot:', error);
    }
    return false;
  }
}

export async function consumePlannerLaunchRoute() {
  if (!isAndroidNative()) return null;

  try {
    const result = await PlannerWidget.consumeLaunchRoute();
    return result?.route || null;
  } catch (error) {
    if (import.meta.env.DEV) {
      console.warn('Failed to consume planner launch route:', error);
    }
    return null;
  }
}
