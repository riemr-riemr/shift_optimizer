/**
 * 共通タスクツリーコンポーネント
 * 作業計画画面と月次作業画面で共通利用
 */

class TaskTree {
  constructor(options = {}) {
    this.containerElement = options.container;
    this.masters = options.masters || [];
    this.categories = options.categories || [];
    this.onTaskSelect = options.onTaskSelect || (() => {});
    this.mode = options.mode || 'single'; // 'single' or 'multi'
    this.selectedTasks = new Set();
    this.selectedTaskCode = null;
  }

  // カテゴリマップを構築
  buildCategoryMap(list) {
    const map = new Map();
    if (!Array.isArray(list)) return [];
    
    // Create category lookup map
    const categoryLookup = new Map();
    this.categories.forEach(cat => {
      categoryLookup.set(cat.categoryCode, cat);
    });
    
    list.forEach(m => {
      const code = m.categoryCode || '__UNCAT__';
      const categoryInfo = categoryLookup.get(code);
      const name = categoryInfo ? categoryInfo.categoryName : (m.categoryName || '未分類');
      const color = categoryInfo ? categoryInfo.color : '#6c757d';
      const icon = categoryInfo ? categoryInfo.icon : '';
      
      // Determine task display color (task color or fallback to category color)
      let taskDisplayColor = color; // Default to category color
      if (m.color && m.color.trim() !== '') {
        taskDisplayColor = m.color; // Use task color if available
      }
      
      if (!map.has(code)) {
        map.set(code, { 
          name, 
          color, 
          icon, 
          tasks: [], 
          categoryCode: code 
        });
      }
      map.get(code).tasks.push({
        ...m,
        displayColor: taskDisplayColor
      });
    });
    
    // sort categories by name, tasks by name
    const sorted = Array.from(map.values()).sort((a,b)=>a.name.localeCompare(b.name));
    sorted.forEach(cat => cat.tasks.sort((a,b)=>a.name.localeCompare(b.name)));
    return sorted;
  }

  // ツリーを描画
  render() {
    if (!this.containerElement) return;
    
    console.log('TaskTree render - masters:', this.masters);
    console.log('TaskTree render - categories:', this.categories);
    
    this.containerElement.innerHTML = '';
    const cats = this.buildCategoryMap(this.masters);
    
    console.log('TaskTree render - built categories:', cats);
    
    if (!cats || cats.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'list-group-item text-muted text-center py-4';
      empty.innerHTML = '<i class="bi bi-exclamation-triangle"></i><br>作業マスタがありません<br><small>管理者にお問い合わせください</small>';
      this.containerElement.appendChild(empty);
      return;
    }

    cats.forEach(cat => {
      const catItem = document.createElement('button');
      catItem.type = 'button';
      catItem.className = 'list-group-item list-group-item-action category';
      catItem.style.borderLeft = `4px solid ${cat.color || '#6c757d'}`;
      
      const chev = document.createElement('span'); 
      chev.className = 'chev';
      
      // Add icon if available
      if (cat.icon) {
        const iconEl = document.createElement('i');
        iconEl.className = cat.icon;
        iconEl.style.marginRight = '8px';
        catItem.appendChild(iconEl);
      }
      
      const label = document.createElement('span'); 
      label.textContent = cat.name;
      catItem.appendChild(chev);
      catItem.appendChild(label);

      const ul = document.createElement('div');
      ul.style.display = 'none';
      
      cat.tasks.forEach(t => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'list-group-item list-group-item-action task';
        btn.style.borderLeft = `4px solid ${t.displayColor}`;
        btn.innerHTML = `<span class="task-code">${t.taskCode}</span> <span class="task-name">${t.name}</span>`;
        btn.dataset.taskCode = t.taskCode;
        btn.dataset.categoryCode = cat.categoryCode;
        btn.dataset.taskData = JSON.stringify(t);
        
        btn.addEventListener('click', (e) => {
          e.stopPropagation();
          this.selectTask(t.taskCode, t);
        });
        
        ul.appendChild(btn);
      });
      
      this.containerElement.appendChild(catItem);
      this.containerElement.appendChild(ul);
      
      catItem.addEventListener('click', () => {
        const open = ul.style.display !== 'none';
        ul.style.display = open ? 'none' : '';
        chev.classList.toggle('open', !open);
      });
    });
  }

  // タスク選択
  selectTask(taskCode, taskData) {
    if (this.mode === 'single') {
      // 単一選択モード
      document.querySelectorAll('.task-tree .list-group-item.task.selected').forEach(e => 
        e.classList.remove('selected'));
      
      const taskElement = this.containerElement.querySelector(`[data-task-code="${taskCode}"]`);
      if (taskElement) {
        taskElement.classList.add('selected');
      }
      
      this.selectedTaskCode = taskCode;
      this.onTaskSelect(taskCode, taskData);
    } else {
      // 複数選択モード（今後の拡張用）
      if (this.selectedTasks.has(taskCode)) {
        this.selectedTasks.delete(taskCode);
      } else {
        this.selectedTasks.add(taskCode);
      }
      this.onTaskSelect(Array.from(this.selectedTasks), taskData);
    }
  }

  // 選択をクリア
  clearSelection() {
    document.querySelectorAll('.task-tree .list-group-item.task.selected').forEach(e => 
      e.classList.remove('selected'));
    this.selectedTaskCode = null;
    this.selectedTasks.clear();
  }

  // データを更新
  updateData(masters, categories) {
    this.masters = masters || [];
    this.categories = categories || [];
    this.render();
  }

  // 選択されたタスクコードを取得
  getSelectedTaskCode() {
    return this.selectedTaskCode;
  }

  // 選択されたタスクデータを取得
  getSelectedTaskData() {
    const taskElement = this.containerElement.querySelector(`[data-task-code="${this.selectedTaskCode}"]`);
    if (taskElement && taskElement.dataset.taskData) {
      return JSON.parse(taskElement.dataset.taskData);
    }
    return null;
  }
}

// スタイルを動的に追加
if (!document.getElementById('task-tree-styles')) {
  const style = document.createElement('style');
  style.id = 'task-tree-styles';
  style.textContent = `
    .task-tree { 
      width: 100%; 
      max-height: 500px; 
      overflow: auto; 
    }
    .task-tree .list-group-item.category { 
      font-weight: 600; 
      cursor: pointer; 
      display: flex; 
      align-items: center; 
      gap: 6px; 
      background-color: #f8f9fa;
      border-bottom: 1px solid #dee2e6;
    }
    .task-tree .list-group-item.task { 
      padding-left: 28px; 
      cursor: pointer; 
      display: flex;
      align-items: center;
      gap: 8px;
      transition: background-color 0.15s ease;
    }
    .task-tree .list-group-item.task:hover {
      background-color: #e9ecef;
    }
    .task-tree .list-group-item.task.selected { 
      background: #0d6efd !important; 
      color: #fff !important; 
    }
    .task-tree .task-code {
      font-family: monospace;
      font-size: 0.9em;
      font-weight: 600;
      min-width: 120px;
    }
    .task-tree .task-name {
      flex: 1;
    }
    .chev { 
      width: 0; 
      height: 0; 
      border-left: 5px solid transparent; 
      border-right: 5px solid transparent; 
      border-top: 6px solid #6c757d; 
      display: inline-block; 
      transform: rotate(-90deg); 
      transition: transform .15s; 
    }
    .chev.open { 
      transform: rotate(0deg); 
    }
  `;
  document.head.appendChild(style);
}

// グローバルに公開
window.TaskTree = TaskTree;