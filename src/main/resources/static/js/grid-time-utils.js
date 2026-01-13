// Shared time/slot helpers for grid-based screens (expects global RES_MIN)
(function () {
  function resMin() {
    return (typeof RES_MIN === 'number' && RES_MIN > 0) ? RES_MIN : 15;
  }

  if (!window.buildTimeSlots) {
    window.buildTimeSlots = function () {
      const slots = [];
      const step = resMin();
      for (let h = 0; h < 24; h++) {
        for (let m = 0; m < 60; m += step) {
          slots.push(`${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`);
        }
      }
      return slots;
    };
  }

  if (!window.slotToTime) {
    window.slotToTime = function (slotIndex) {
      const step = resMin();
      const totalMinutes = slotIndex * step;
      const hour = Math.floor(totalMinutes / 60);
      const minute = totalMinutes % 60;
      return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
    };
  }

  if (!window.timeToSlot) {
    window.timeToSlot = function (timeStr) {
      if (!timeStr) return -1;
      try {
        let hours, minutes;
        if (timeStr instanceof Date) {
          hours = timeStr.getHours();
          minutes = timeStr.getMinutes();
        } else if (typeof timeStr === 'string') {
          const s = timeStr.trim();
          const hhmm = /^\d{1,2}:\d{2}$/;
          const hhmmss = /^\d{1,2}:\d{2}:\d{2}$/;
          if (hhmm.test(s) || hhmmss.test(s)) {
            const parts = s.split(':');
            hours = parseInt(parts[0], 10);
            minutes = parseInt(parts[1], 10);
          } else {
            const d = new Date(s);
            if (isNaN(d.getTime())) return -1;
            hours = d.getHours();
            minutes = d.getMinutes();
          }
        } else {
          const d = new Date(timeStr);
          if (isNaN(d.getTime())) return -1;
          hours = d.getHours();
          minutes = d.getMinutes();
        }
        return Math.floor((hours * 60 + minutes) / resMin());
      } catch (e) {
        console.warn(`timeToSlot error for ${timeStr}:`, e);
        return -1;
      }
    };
  }

  if (!window.timeLabelFromSlot) {
    window.timeLabelFromSlot = function (slot) {
      const step = resMin();
      const startMin = slot * step;
      const h = Math.floor(startMin / 60);
      const m = startMin % 60;
      return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
    };
  }
})();
