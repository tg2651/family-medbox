// 家庭用药管理服务端 —— Node.js + Express + SQLite（node:sqlite 内置模块，零原生依赖）
const express = require('express');
const { DatabaseSync } = require('node:sqlite');
const crypto = require('crypto');
const path = require('path');
const fs = require('fs');
const ai = require('./aiRecognize');

const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
fs.mkdirSync(DATA_DIR, { recursive: true });
fs.mkdirSync(path.join(DATA_DIR, 'uploads'), { recursive: true });
const db = new DatabaseSync(path.join(DATA_DIR, 'medbox.db'));
db.exec('PRAGMA journal_mode = WAL');
// 旧库兼容：补 image_url 列
try { db.exec('ALTER TABLE medicines ADD COLUMN image_url TEXT DEFAULT \'\''); } catch (e) {}

// ---------- 工具 ----------
function hashPwd(pw, salt) {
  salt = salt || crypto.randomBytes(16).toString('hex');
  const h = crypto.scryptSync(pw, salt, 32).toString('hex');
  return `${salt}:${h}`;
}
function verifyPwd(pw, stored) {
  const [salt, h] = stored.split(':');
  return crypto.scryptSync(pw, salt, 32).toString('hex') === h;
}
const TOKENS = new Map(); // token -> {userId, familyId, exp}
function issueToken(user) {
  const t = crypto.randomBytes(24).toString('hex');
  TOKENS.set(t, { userId: user.id, familyId: user.family_id, exp: Date.now() + 30 * 864e5 });
  return t;
}
function authUser(req) {
  const t = (req.headers.authorization || '').replace('Bearer ', '');
  const info = TOKENS.get(t);
  if (!info || info.exp < Date.now()) return null;
  return db.prepare('SELECT * FROM users WHERE id=?').get(info.userId);
}
function requireAuth(req, res, next) {
  const u = authUser(req);
  if (!u) return res.status(401).json({ error: '未登录或登录已过期' });
  req.user = u;
  next();
}
function requireAdmin(req, res, next) {
  if (req.user.role !== 'admin') return res.status(403).json({ error: '需要管理员权限' });
  next();
}
const bad = (res, msg) => res.status(400).json({ error: msg });

// ---------- 建表 ----------
db.exec(`
CREATE TABLE IF NOT EXISTS families(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  invite_code TEXT UNIQUE NOT NULL,
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS users(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  family_id INTEGER NOT NULL REFERENCES families(id),
  username TEXT UNIQUE NOT NULL,
  password TEXT NOT NULL,
  display_name TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'member',
  age INTEGER, notes TEXT,
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS medicines(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  family_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL REFERENCES users(id),
  name TEXT NOT NULL,
  spec TEXT DEFAULT '',
  category TEXT DEFAULT '',
  expiry_date TEXT DEFAULT '',
  stock_qty REAL NOT NULL DEFAULT 0,
  unit TEXT DEFAULT '盒',
  daily_dose REAL NOT NULL DEFAULT 0,
  dose_unit TEXT DEFAULT '片',
  location TEXT DEFAULT '',
  notes TEXT DEFAULT '',
  image_url TEXT DEFAULT '',
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS schedules(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  family_id INTEGER NOT NULL,
  medicine_id INTEGER NOT NULL REFERENCES medicines(id),
  user_id INTEGER NOT NULL REFERENCES users(id),
  times TEXT NOT NULL,             -- JSON: ["08:00","20:00"]
  dose_per_time REAL NOT NULL DEFAULT 1,
  dose_unit TEXT DEFAULT '片',
  frequency TEXT NOT NULL DEFAULT 'daily', -- daily | interval
  interval_days INTEGER DEFAULT 1,
  start_date TEXT NOT NULL,
  end_date TEXT DEFAULT '',
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS intake_logs(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  schedule_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  medicine_id INTEGER NOT NULL,
  plan_date TEXT NOT NULL,
  plan_time TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending', -- pending | taken | skipped
  taken_at TEXT
);
`);

// ---------- 计算辅助 ----------
function todayStr() {
  const d = new Date();
  const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
function daysBetween(a, b) { // a - b 天数
  return Math.floor((new Date(a + 'T00:00:00') - new Date(b + 'T00:00:00')) / 864e5);
}
// 开药提醒：按每日用量推算可服天数
function refillInfo(m) {
  if (!m.daily_dose || m.daily_dose <= 0 || m.stock_qty == null) return null;
  const days = Math.floor(m.stock_qty / m.daily_dose);
  return { daysLeft: days, needRefill: days <= 7 };
}
function expiryInfo(m) {
  if (!m.expiry_date) return null;
  const days = daysBetween(m.expiry_date, todayStr());
  return { daysLeft: days, nearExpiry: days <= 30, expired: days < 0 };
}
function scheduleOccursOn(sc, dateStr) {
  const s = new Date(sc.start_date + 'T00:00:00');
  const d = new Date(dateStr + 'T00:00:00');
  if (d < s) return false;
  if (sc.end_date && d > new Date(sc.end_date + 'T00:00:00')) return false;
  if (sc.frequency === 'daily') return true;
  return daysBetween(dateStr, sc.start_date) % Math.max(1, sc.interval_days) === 0;
}
function getUserMedicines(userId) {
  return db.prepare('SELECT * FROM medicines WHERE user_id=? ORDER BY id DESC').all(userId);
}
function getSchedules(userId) {
  return db.prepare('SELECT s.*, m.name AS medicine_name, m.stock_qty, m.unit FROM schedules s JOIN medicines m ON m.id=s.medicine_id WHERE s.user_id=? ORDER BY s.id DESC').all(userId);
}

// ---------- API ----------
const app = express();
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));
// 兼容部分 HTTP 客户端（如安卓 HttpURLConnection）无法直接发送 PATCH：POST + X-HTTP-Method-Override 头转换
app.use((req, res, next) => {
  const ov = req.headers['x-http-method-override'];
  if (ov && req.method === 'POST') req.method = String(ov).toUpperCase();
  next();
});
app.use(express.static(path.join(__dirname, 'public')));
// 上传的药品图片
app.use('/uploads', express.static(path.join(DATA_DIR, 'uploads')));

// --- 认证 ---
app.post('/api/auth/register', (req, res) => {
  const { username, password, display_name, invite_code, age, notes } = req.body;
  if (!username || !password || !display_name) return bad(res, '用户名、密码、姓名必填');
  if (db.prepare('SELECT id FROM users WHERE username=?').get(username)) return bad(res, '用户名已存在');
  let familyId, role = 'member', familyName;
  if (invite_code) {
    const f = db.prepare('SELECT * FROM families WHERE invite_code=?').get(invite_code.trim().toUpperCase());
    if (!f) return bad(res, '邀请码无效');
    familyId = f.id; familyName = f.name;
  } else {
    familyName = req.body.family_name || (display_name + '的家庭');
    const code = crypto.randomBytes(3).toString('hex').toUpperCase();
    familyId = Number(db.prepare('INSERT INTO families(name, invite_code) VALUES(?,?)').run(familyName, code).lastInsertRowid);
    role = 'admin'; // 创建家庭者为管理员
  }
  const info = db.prepare('INSERT INTO users(family_id,username,password,display_name,role,age,notes) VALUES(?,?,?,?,?,?,?)')
    .run(familyId, username, hashPwd(password), display_name, role, age || null, notes || '');
  const user = db.prepare('SELECT * FROM users WHERE id=?').get(Number(info.lastInsertRowid));
  res.json({ token: issueToken(user), user: pubUser(user), family: { id: familyId, name: familyName } });
});

app.post('/api/auth/login', (req, res) => {
  const { username, password } = req.body;
  const user = db.prepare('SELECT * FROM users WHERE username=?').get(username);
  if (!user || !verifyPwd(password || '', user.password)) return bad(res, '用户名或密码错误');
  res.json({ token: issueToken(user), user: pubUser(user) });
});

function pubUser(u) {
  return { id: u.id, username: u.username, display_name: u.display_name, role: u.role, age: u.age, family_id: u.family_id };
}

app.get('/api/me', requireAuth, (req, res) => {
  const f = db.prepare('SELECT * FROM families WHERE id=?').get(req.user.family_id);
  res.json({ user: pubUser(req.user), family: f ? { id: f.id, name: f.name, invite_code: f.invite_code } : null });
});

// --- 家庭成员 ---
app.get('/api/family/members', requireAuth, (req, res) => {
  const rows = db.prepare('SELECT id,username,display_name,role,age,notes,created_at FROM users WHERE family_id=? ORDER BY id').all(req.user.family_id);
  const out = rows.map(m => {
    const meds = getUserMedicines(m.id);
    return { ...m, medicine_count: meds.length, schedule_count: getSchedules(m.id).length };
  });
  res.json(out);
});

app.patch('/api/family/members/:id', requireAuth, requireAdmin, (req, res) => {
  const m = db.prepare('SELECT * FROM users WHERE id=? AND family_id=?').get(req.params.id, req.user.family_id);
  if (!m) return bad(res, '成员不存在');
  const { display_name, age, notes } = req.body;
  db.prepare('UPDATE users SET display_name=COALESCE(?,display_name), age=COALESCE(?,age), notes=COALESCE(?,notes) WHERE id=?')
    .run(display_name ?? null, age ?? null, notes ?? null, m.id);
  res.json({ ok: true });
});

app.post('/api/family/invite-code', requireAuth, requireAdmin, (req, res) => {
  const code = crypto.randomBytes(3).toString('hex').toUpperCase();
  db.prepare('UPDATE families SET invite_code=? WHERE id=?').run(code, req.user.family_id);
  res.json({ invite_code: code });
});

// --- 药品 ---
app.get('/api/medicines', requireAuth, (req, res) => {
  let uid = req.user.id;
  if (req.query.user_id) { // 管理员可看他人
    if (req.user.role !== 'admin') return bad(res, '无权限');
    uid = +req.query.user_id;
  }
  const meds = getUserMedicines(uid).map(m => ({ ...m, refill: refillInfo(m), expiry: expiryInfo(m) }));
  res.json(meds);
});

// --- 药品图片上传（base64） ---
app.post('/api/upload', requireAuth, (req, res) => {
  const data = (req.body.image || '').replace(/^data:image\/\w+;base64,/, '');
  if (!data) return bad(res, '图片数据为空');
  const buf = Buffer.from(data, 'base64');
  if (buf.length > 8 * 1024 * 1024) return bad(res, '图片过大（限 8MB）');
  const name = `med_${Date.now()}_${Math.random().toString(36).slice(2, 8)}.jpg`;
  fs.writeFileSync(path.join(DATA_DIR, 'uploads', name), buf);
  res.json({ url: `/uploads/${name}` });
});

// --- AI 识别（药品包装 / 处方） ---
app.post('/api/ai/recognize', requireAuth, async (req, res) => {
  try {
    const data = (req.body.image || '').replace(/^data:image\/\w+;base64,/, '');
    if (!data) return bad(res, '图片数据为空');
    const type = req.body.type === 'prescription' ? 'prescription' : 'medicine';
    const buf = Buffer.from(data, 'base64');
    if (buf.length > 8 * 1024 * 1024) return bad(res, '图片过大（限 8MB）');
    const r = type === 'prescription' ? await ai.recognizePrescription(data) : await ai.recognizeMedicine(data);
    res.json(r);
  } catch (e) { bad(res, e.message || 'AI识别失败'); }
});

app.post('/api/medicines', requireAuth, (req, res) => {
  const { name, spec, category, expiry_date, stock_qty, unit, daily_dose, dose_unit, location, notes, image_url } = req.body;
  if (!name) return bad(res, '药品名称必填');
  let uid = req.user.id;
  if (req.body.user_id && req.user.role === 'admin') uid = +req.body.user_id;
  const info = db.prepare(`INSERT INTO medicines(family_id,user_id,name,spec,category,expiry_date,stock_qty,unit,daily_dose,dose_unit,location,notes,image_url)
    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)`)
    .run(req.user.family_id, uid, name, spec || '', category || '', expiry_date || '', +stock_qty || 0, unit || '盒', +daily_dose || 0, dose_unit || '片', location || '', notes || '', image_url || '');
  res.json({ id: Number(info.lastInsertRowid) });
});

app.patch('/api/medicines/:id', requireAuth, (req, res) => {
  const m = db.prepare('SELECT * FROM medicines WHERE id=?').get(req.params.id);
  if (!m || m.family_id !== req.user.family_id) return bad(res, '药品不存在');
  if (m.user_id !== req.user.id && req.user.role !== 'admin') return bad(res, '无权限');
  const f = ['name', 'spec', 'category', 'expiry_date', 'stock_qty', 'unit', 'daily_dose', 'dose_unit', 'location', 'notes', 'image_url'];
  const sets = [], vals = [];
  for (const k of f) if (req.body[k] !== undefined) { sets.push(`${k}=?`); vals.push(req.body[k]); }
  if (sets.length) db.prepare(`UPDATE medicines SET ${sets.join(',')} WHERE id=?`).run(...vals, m.id);
  res.json({ ok: true });
});

app.delete('/api/medicines/:id', requireAuth, (req, res) => {
  const m = db.prepare('SELECT * FROM medicines WHERE id=?').get(req.params.id);
  if (!m || m.family_id !== req.user.family_id) return bad(res, '药品不存在');
  if (m.user_id !== req.user.id && req.user.role !== 'admin') return bad(res, '无权限');
  db.prepare('DELETE FROM schedules WHERE medicine_id=?').run(m.id);
  db.prepare('DELETE FROM medicines WHERE id=?').run(m.id);
  res.json({ ok: true });
});

// --- 用药计划 ---
app.get('/api/schedules', requireAuth, (req, res) => {
  let uid = req.user.id;
  if (req.query.user_id) {
    if (req.user.role !== 'admin') return bad(res, '无权限');
    uid = +req.query.user_id;
  }
  res.json(getSchedules(uid));
});

app.post('/api/schedules', requireAuth, (req, res) => {
  const { medicine_id, times, dose_per_time, dose_unit, frequency, interval_days, start_date, end_date } = req.body;
  if (!medicine_id || !times || !times.length || !start_date) return bad(res, '药品、时间、开始日期必填');
  const m = db.prepare('SELECT * FROM medicines WHERE id=?').get(medicine_id);
  if (!m || m.family_id !== req.user.family_id) return bad(res, '药品不存在');
  let uid = req.user.id;
  if (m.user_id !== req.user.id && req.user.role !== 'admin') uid = m.user_id;
  const info = db.prepare(`INSERT INTO schedules(family_id,medicine_id,user_id,times,dose_per_time,dose_unit,frequency,interval_days,start_date,end_date)
    VALUES(?,?,?,?,?,?,?,?,?,?)`)
    .run(req.user.family_id, medicine_id, uid, JSON.stringify(times), +dose_per_time || 1, dose_unit || '片',
      frequency || 'daily', Math.max(1, +interval_days || 1), start_date, end_date || '');
  res.json({ id: Number(info.lastInsertRowid) });
});

app.patch('/api/schedules/:id', requireAuth, (req, res) => {
  const sc = db.prepare('SELECT * FROM schedules WHERE id=?').get(req.params.id);
  if (!sc || sc.family_id !== req.user.family_id) return bad(res, '计划不存在');
  if (sc.user_id !== req.user.id && req.user.role !== 'admin') return bad(res, '无权限');
  const body = { ...req.body };
  if (Array.isArray(body.times)) body.times = JSON.stringify(body.times);
  const f = ['times', 'dose_per_time', 'dose_unit', 'frequency', 'interval_days', 'start_date', 'end_date', 'enabled'];
  const sets = [], vals = [];
  for (const k of f) if (body[k] !== undefined) { sets.push(`${k}=?`); vals.push(body[k]); }
  if (sets.length) db.prepare(`UPDATE schedules SET ${sets.join(',')} WHERE id=?`).run(...vals, sc.id);
  res.json({ ok: true });
});

app.delete('/api/schedules/:id', requireAuth, (req, res) => {
  const sc = db.prepare('SELECT * FROM schedules WHERE id=?').get(req.params.id);
  if (!sc || sc.family_id !== req.user.family_id) return bad(res, '计划不存在');
  if (sc.user_id !== req.user.id && req.user.role !== 'admin') return bad(res, '无权限');
  db.prepare('DELETE FROM schedules WHERE id=?').run(sc.id);
  db.prepare('DELETE FROM intake_logs WHERE schedule_id=?').run(sc.id);
  res.json({ ok: true });
});

// --- 今日服药排程 ---
app.get('/api/today', requireAuth, (req, res) => {
  const dateStr = req.query.date || todayStr();
  const uid = req.query.user_id && req.user.role === 'admin' ? +req.query.user_id : req.user.id;
  const scs = db.prepare('SELECT * FROM schedules WHERE user_id=? AND enabled=1').all(uid);
  const items = [];
  for (const sc of scs) {
    if (!scheduleOccursOn(sc, dateStr)) continue;
    const m = db.prepare('SELECT * FROM medicines WHERE id=?').get(sc.medicine_id);
    if (!m) continue;
    const logs = db.prepare('SELECT * FROM intake_logs WHERE schedule_id=? AND plan_date=?').all(sc.id, dateStr);
    for (const t of JSON.parse(sc.times)) {
      const log = logs.find(l => l.plan_time === t);
      items.push({
        schedule_id: sc.id, medicine_id: m.id, medicine_name: m.name, plan_time: t,
        dose: sc.dose_per_time, dose_unit: sc.dose_unit,
        status: log ? log.status : 'pending', log_id: log ? log.id : null, taken_at: log ? log.taken_at : null
      });
    }
  }
  items.sort((a, b) => a.plan_time.localeCompare(b.plan_time));
  res.json(items);
});

app.post('/api/intake', requireAuth, (req, res) => {
  const { schedule_id, plan_time, status, date } = req.body;
  const sc = db.prepare('SELECT * FROM schedules WHERE id=?').get(schedule_id);
  if (!sc || sc.family_id !== req.user.family_id) return bad(res, '计划不存在');
  if (sc.user_id !== req.user.id && req.user.role !== 'admin') return bad(res, '无权限');
  const dateStr = date || todayStr();
  let log = db.prepare('SELECT * FROM intake_logs WHERE schedule_id=? AND plan_date=? AND plan_time=?').get(sc.id, dateStr, plan_time);
  const st = status || 'taken';
  if (log) {
    db.prepare('UPDATE intake_logs SET status=?, taken_at=? WHERE id=?')
      .run(st, st === 'pending' ? null : new Date().toISOString(), log.id);
  } else {
    db.prepare('INSERT INTO intake_logs(schedule_id,user_id,medicine_id,plan_date,plan_time,status,taken_at) VALUES(?,?,?,?,?,?,?)')
      .run(sc.id, sc.user_id, sc.medicine_id, dateStr, plan_time, st, st === 'pending' ? null : new Date().toISOString());
  }
  // 服药后扣减库存
  if (st === 'taken') {
    db.prepare('UPDATE medicines SET stock_qty = MAX(0, stock_qty - ?) WHERE id=?').run(sc.dose_per_time, sc.medicine_id);
  }
  res.json({ ok: true });
});

// --- 预警：低库存（该开药了）与临期 ---
app.get('/api/alerts', requireAuth, (req, res) => {
  let uid = req.query.user_id && req.user.role === 'admin' ? +req.query.user_id : null;
  const meds = uid
    ? getUserMedicines(uid)
    : db.prepare('SELECT * FROM medicines WHERE family_id=?').all(req.user.family_id);
  const refill = [], expiry = [];
  for (const m of meds) {
    const owner = db.prepare('SELECT display_name FROM users WHERE id=?').get(m.user_id);
    const r = refillInfo(m), e = expiryInfo(m);
    if (r && r.needRefill) refill.push({ ...m, owner: owner?.display_name, days_left: r.daysLeft });
    if (e && (e.expired || e.nearExpiry)) expiry.push({ ...m, owner: owner?.display_name, days_left: e.daysLeft, expired: e.expired });
  }
  refill.sort((a, b) => a.days_left - b.days_left);
  expiry.sort((a, b) => a.days_left - b.days_left);
  res.json({ refill, expiry });
});

// --- 管理员总览 ---
app.get('/api/admin/overview', requireAuth, requireAdmin, (req, res) => {
  const members = db.prepare('SELECT id,username,display_name,role,age,notes,created_at FROM users WHERE family_id=? ORDER BY id').all(req.user.family_id);
  const today = todayStr();
  const result = members.map(m => {
    const meds = getUserMedicines(m.id).map(md => ({ ...md, refill: refillInfo(md), expiry: expiryInfo(md) }));
    const scs = getSchedules(m.id);
    // 今日待办统计
    let pending = 0, total = 0;
    for (const sc of db.prepare('SELECT * FROM schedules WHERE user_id=? AND enabled=1').all(m.id)) {
      if (!scheduleOccursOn(sc, today)) continue;
      const logs = db.prepare('SELECT * FROM intake_logs WHERE schedule_id=? AND plan_date=?').all(sc.id, today);
      for (const t of JSON.parse(sc.times)) {
        total++;
        const l = logs.find(x => x.plan_time === t);
        if (!l || l.status === 'pending') pending++;
      }
    }
    return { ...m, medicines: meds, schedules: scs, today_total: total, today_pending: pending };
  });
  const alerts = { refill: [], expiry: [] };
  for (const mem of result) {
    for (const md of mem.medicines) {
      if (md.refill && md.refill.needRefill) alerts.refill.push({ medicine_id: md.id, name: md.name, owner: mem.display_name, days_left: md.refill.daysLeft, stock_qty: md.stock_qty, unit: md.unit, daily_dose: md.daily_dose });
      if (md.expiry && (md.expiry.expired || md.expiry.nearExpiry)) alerts.expiry.push({ medicine_id: md.id, name: md.name, owner: mem.display_name, days_left: md.expiry.days_left, expired: md.expiry.expired, expiry_date: md.expiry_date });
    }
  }
  alerts.refill.sort((a, b) => a.days_left - b.days_left);
  alerts.expiry.sort((a, b) => a.days_left - b.days_left);
  res.json({ members: result, alerts });
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, '0.0.0.0', () => console.log(`家庭用药服务已启动: http://0.0.0.0:${PORT}  (后台: /admin.html)`));
