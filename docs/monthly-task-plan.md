Monthly Task Plan (DOM/WOM)

Overview
- Stores monthly recurring task plans separate from weekly/special `task_plan`.
- Two selection patterns supported:
  - DOM: Specific days of month (1..31)
  - WOM: Week-of-month (1..5) x ISO day-of-week (1..7; Mon=1)

Schema
- `monthly_task_plan`: Common plan fields (store, dept, task, schedule, effective range).
- `monthly_task_plan_dom`: Child rows with `day_of_month` (1..31).
- `monthly_task_plan_wom`: Child rows with `(week_of_month, day_of_week)` pairs.

API
- Create DOM plan:
  - POST `/tasks/api/monthly/dom`
  - Body (JSON):
    { "storeCode":"S01", "departmentCode":"520", "taskCode":"CLEAN", "scheduleType":"FIXED",
      "fixedStartTime":"09:00", "fixedEndTime":"10:00", "requiredStaffCount":1,
      "effectiveFrom":"2025-01-01", "effectiveTo":null, "daysOfMonth":[1,15,31] }

- Create WOM plan:
  - POST `/tasks/api/monthly/wom`
  - Body (JSON):
    { "storeCode":"S01", "departmentCode":"520", "taskCode":"STOCK", "scheduleType":"FLEXIBLE",
      "windowStartTime":"13:00", "windowEndTime":"17:00", "requiredDurationMinutes":120,
      "weeksOfMonth":[1,3], "daysOfWeek":[2,5] }

- List effective plans on a date (debug/verification):
  - GET `/tasks/api/monthly/effective?store=S01&date=2025-02-15`

Generation
- Monthly plans are applied during existing `TaskPlanService.generate(...)` alongside weekly and special plans.
- Week-of-month is computed as `((day-1)/7)+1` with day-of-week = ISO (Mon=1..Sun=7).

Security
- CSRF is ignored for `/tasks/api/**` similar to other API endpoints.

