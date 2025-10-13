// Shared skill matrix behaviors: range selection and bulk apply
// Usage: SkillMatrix.initRangeSelection('#tableId');

window.SkillMatrix = (function() {
  let isSelecting = false;
  let startCell = null;
  let selectedCells = new Set();

  function getCellPosition(cell) {
    const row = cell.closest('tr');
    const tbody = cell.closest('tbody');
    if (!row || !tbody) return null;
    const rowIndex = Array.from(tbody.querySelectorAll('tr')).indexOf(row);
    const cellIndex = Array.from(row.querySelectorAll('td')).indexOf(cell) - 0; // 0-based
    return { row: rowIndex, col: cellIndex };
  }

  function clearTempSelection(table) {
    table.querySelectorAll('td.skill-cell.selecting').forEach(c => c.classList.remove('selecting'));
  }

  function updateRangeSelection(table, currentCell) {
    if (!isSelecting || !startCell) return;
    clearTempSelection(table);
    selectedCells.clear();
    const s = getCellPosition(startCell);
    const c = getCellPosition(currentCell);
    if (!s || !c) return;
    const minRow = Math.min(s.row, c.row);
    const maxRow = Math.max(s.row, c.row);
    const minCol = Math.min(s.col, c.col);
    const maxCol = Math.max(s.col, c.col);
    const rows = table.querySelectorAll('tbody tr');
    for (let r = minRow; r <= maxRow; r++) {
      const row = rows[r];
      if (!row) continue;
      const cells = row.querySelectorAll('td.skill-cell');
      for (let cc = minCol; cc <= maxCol; cc++) {
        const cell = cells[cc];
        if (!cell) continue;
        cell.classList.add('selecting');
        selectedCells.add(cell);
      }
    }
  }

  function showPanel() {
    const panel = document.getElementById('rangeSelectorPanel');
    if (!panel) return;
    document.getElementById('selectedCount').textContent = selectedCells.size;
    panel.classList.add('show');
  }

  function hidePanel() {
    const panel = document.getElementById('rangeSelectorPanel');
    if (!panel) return;
    panel.classList.remove('show');
  }

  function applyBulk(value) {
    selectedCells.forEach(cell => {
      const sel = cell.querySelector('select');
      if (sel) {
        sel.value = String(value);
      }
    });
    hidePanel();
  }

  function initRangeSelection(tableSelector) {
    const table = document.querySelector(tableSelector);
    if (!table) return;
    // mouse events
    table.addEventListener('mousedown', (e) => {
      const cell = e.target.closest('td.skill-cell');
      if (!cell) return;
      isSelecting = true;
      startCell = cell;
      selectedCells.clear();
      table.querySelectorAll('td.skill-cell.selected, td.skill-cell.selecting').forEach(c => c.classList.remove('selected','selecting'));
      cell.classList.add('selecting');
      selectedCells.add(cell);
      e.preventDefault();
    });
    table.addEventListener('mouseover', (e) => {
      if (!isSelecting) return;
      const cell = e.target.closest('td.skill-cell');
      if (!cell) return;
      updateRangeSelection(table, cell);
    });
    document.addEventListener('mouseup', () => {
      if (!isSelecting) return;
      isSelecting = false;
      // finalize selection
      table.querySelectorAll('td.skill-cell.selecting').forEach(c => {
        c.classList.remove('selecting');
        c.classList.add('selected');
      });
      showPanel();
    });

    // panel controls
    const panel = document.getElementById('rangeSelectorPanel');
    if (panel) {
      panel.querySelectorAll('.bulk-action-btn').forEach(btn => {
        btn.addEventListener('click', () => {
          const val = btn.getAttribute('data-value');
          applyBulk(val);
        });
      });
      const clearBtn = document.getElementById('clearSelection');
      if (clearBtn) clearBtn.addEventListener('click', () => {
        table.querySelectorAll('td.skill-cell.selected').forEach(c => c.classList.remove('selected'));
        selectedCells.clear();
        hidePanel();
      });
      const cancelBtn = document.getElementById('cancelRange');
      if (cancelBtn) cancelBtn.addEventListener('click', hidePanel);
      const closeBtn = document.getElementById('closeRangePanel');
      if (closeBtn) closeBtn.addEventListener('click', hidePanel);
    }
  }

  return { initRangeSelection };
})();

