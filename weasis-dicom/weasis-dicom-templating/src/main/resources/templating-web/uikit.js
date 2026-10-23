// Page helpers for the templating page (round 6, 13 Oct 2026; Sami's requests and Astra's review of the design): which
// key presses are shortcuts where, keeping the side panel's focus, scroll and open boxes when it is rebuilt, and the
// phone's resizable panel. Nothing about the plan itself is kept here.

// Where the keyboard focus is. 'text': a field that takes typing (its own keys, never a shortcut). 'editor': the X-ray,
// or nothing in particular (every shortcut). 'control': a button, list, checkbox or slider (only Esc and Undo, which
// these controls do not use themselves; arrows, letters and Tab stay theirs).
const NOT_TEXT = new Set(['button', 'checkbox', 'radio', 'range', 'color', 'file', 'submit', 'reset', 'image', 'hidden']);
export function focusKind(a, editor) {
  if (!a || a === editor || a.tagName === 'BODY' || a.tagName === 'HTML') return 'editor';
  if (a.isContentEditable || a.tagName === 'TEXTAREA') return 'text';
  if (a.tagName === 'INPUT' && !NOT_TEXT.has(String(a.type || 'text').toLowerCase())) return 'text';
  return 'control';
}

// The shortcut a key press stands for, or null (then the page leaves the key alone). e: a KeyboardEvent or its fields.
//   Esc: stop what is armed.  Ctrl/⌘+Z: undo; Ctrl/⌘+Shift+Z and Ctrl+Y: redo (15 Oct 2026).
//   F: fit the X-ray to the window, also with no implant selected.
//   ↑ / ↓ (and PgUp / PgDn, + / -): next / previous size of the selected implant.
//   Shift+arrows: move it 0.5 mm along the screen; Shift+Alt+arrows: 2 mm.  , and .: turn it 0.5° (with Shift 2°).
// Tab is never a shortcut (it moves the focus, as everywhere). Typing in another language (composition) is left alone,
// and so are Ctrl, ⌘ and Alt with any other key.
export function shortcut(e, where) {
  if (e.isComposing || e.keyCode === 229 || where === 'text') return null;
  const mod = e.ctrlKey || e.metaKey, k = e.key;
  if (k === 'Escape') return mod || e.altKey ? null : { act: 'escape' };
  // Ctrl/Cmd+Z undo; Ctrl/Cmd+Shift+Z and Ctrl+Y redo (Sami, 15 Oct: a Redo beside Undo)
  if (mod) return e.altKey ? null : (k === 'z' || k === 'Z') ? { act: e.shiftKey ? 'redo' : 'undo' } : (k === 'y' || k === 'Y') && e.ctrlKey && !e.metaKey && !e.shiftKey ? { act: 'redo' } : null;
  if (where !== 'editor') return null;
  const arrow = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[k];
  if (arrow && e.shiftKey) return { act: 'move', dx: arrow[0], dy: arrow[1], mm: e.altKey ? 2 : 0.5 };
  if (e.altKey) return null;
  if (k === 'f' || k === 'F') return { act: 'fit' };
  if (k === 'ArrowUp' || k === 'PageUp' || k === '+' || k === '=') return { act: 'size', d: 1 };
  if (k === 'ArrowDown' || k === 'PageDown' || k === '-') return { act: 'size', d: -1 };
  if (k === ',' || k === '<') return { act: 'turn', deg: e.shiftKey ? -2 : -0.5 };
  if (k === '.' || k === '>') return { act: 'turn', deg: e.shiftKey ? 2 : 0.5 };
  return null;
}

// ---- the side panel is rebuilt after each change: what it keeps (Astra, 13 Oct: focus, scroll, boxes opened) ----
const FOCUSABLE = 'button, select, input, textarea, summary, a[href], [tabindex]';
const words = s => String(s || '').trim().replace(/\s+/g, ' ').slice(0, 60);
// a name for a control that survives the rebuild: its section, its implant box, its kind and its words
function nameOf(e) {
  const sec = e.closest('details.sec, .next, .steps, .ctx'), box = e.closest('[data-slot]'), lab = e.closest('label');
  const w = e.getAttribute('aria-label') || e.getAttribute('title') || (lab ? lab.textContent : e.tagName === 'SELECT' ? '' : e.textContent);
  return [sec ? sec.id || sec.className.split(' ')[0] : '', box ? box.dataset.slot : '', e.tagName, words(w)].join('|');
}
const detName = d => { const sec = d.parentElement && d.parentElement.closest('details.sec'), s = d.querySelector(':scope > summary'); return (sec ? sec.id : '') + '|' + words(s && s.textContent); };
function pathOf(root, e) { const p = []; for (let n = e; n && n !== root; n = n.parentElement) p.unshift([...n.parentElement.children].indexOf(n)); return p; }
function nodeAt(root, path) { let n = root; for (const i of path || []) { n = n && n.children[i]; } return n && n !== root ? n : null; }
export function keepPanel(root) {
  const a = document.activeElement, st = { top: root.scrollTop, focus: null, open: [] };
  if (a && a !== root && root.contains(a)) {
    const name = nameOf(a), same = [...root.querySelectorAll(FOCUSABLE)].filter(x => nameOf(x) === name);
    st.focus = { name, i: same.indexOf(a), path: pathOf(root, a) };
  }
  for (const d of root.querySelectorAll('details[open]:not(.sec)')) st.open.push(detName(d));
  return st;
}
// focus: put the keyboard focus back on the same control (or the panel itself, never the top of the page); top: the
// same scroll position (false when the step changed: the new step is at the top)
export function restorePanel(root, st, { focus = true, top = true } = {}) {
  if (!st) return;
  for (const d of root.querySelectorAll('details:not(.sec)')) if (!d.open && st.open.includes(detName(d))) d.open = true;
  root.scrollTop = top ? st.top : 0;
  if (!focus || !st.focus) return;
  const same = [...root.querySelectorAll(FOCUSABLE)].filter(x => nameOf(x) === st.focus.name);
  const e = same[st.focus.i] || same[0] || nodeAt(root, st.focus.path);
  if (e && !e.disabled) e.focus({ preventScroll: true });
  if (document.activeElement !== e) root.focus({ preventScroll: true });
}

// ---- the phone's panel under the film: three heights, one handle (round 11, 13 Oct 2026) ----
// peek: one row (the step, its button, the badge of results to check, the handle); half; full (a strip of the film
// stays). The handle, or a tap on the row's free space, cycles peek -> half -> full -> peek; a swipe on the row goes up
// (more) or down (less). Every page load starts at peek (Sami: clean by default). onChange runs after a change.
export function makeSheet({ col, bar, handle, onChange }) {
  const STATES = ['peek', 'half', 'full'];
  let s = 'peek', swiped = false;
  const sync = () => {
    for (const x of STATES) col.classList.toggle(x, x === s);
    const full = s === 'full';
    handle.textContent = full ? '▾' : '▴';
    handle.setAttribute('aria-label', full ? 'Show less of the panel' : 'Show more of the panel');
    handle.title = full ? 'Back to the smallest panel (tap, or swipe down)' : 'Show more of the panel (tap, or swipe up or down)';
    handle.setAttribute('aria-expanded', String(s !== 'peek')); handle.dataset.state = s;
  };
  const set = v => {
    if (!STATES.includes(v) || v === s) return;
    s = v; sync(); if (onChange) onChange(v);
  };
  const step = d => set(STATES[Math.max(0, Math.min(STATES.length - 1, STATES.indexOf(s) + d))]);
  const cycle = () => set(STATES[(STATES.indexOf(s) + 1) % STATES.length]);
  handle.onclick = () => { if (swiped) { swiped = false; return; } cycle(); };
  bar.addEventListener('click', e => { if (swiped) { swiped = false; return; } if (!e.target.closest('button')) cycle(); });
  let y0 = null;   // a swipe: up shows more, down less (the panel is not dragged live: no resizing on every move)
  bar.addEventListener('pointerdown', e => { const b = e.target.closest('button'); if (b && b !== handle) return; y0 = e.clientY; swiped = false; });
  addEventListener('pointerup', e => { if (y0 === null) return; const dy = e.clientY - y0; y0 = null; if (Math.abs(dy) > 24) { swiped = true; step(dy < 0 ? 1 : -1); setTimeout(() => { swiped = false; }, 400); } });
  addEventListener('pointercancel', () => { y0 = null; });
  sync();
  return { get: () => s, set, step, cycle, sync };
}
