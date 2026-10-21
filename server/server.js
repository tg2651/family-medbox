// 家庭用药管理服务端 —— Node.js + Express + SQLite（node:sqlite 内置模块，零原生依赖）
const express = require('express');
const { DatabaseSync } = require('node:sqlite');
const crypto = require('crypto');
const path = require('path');
const fs = require('fs');
const https = require('https');
const ai = require('./aiRecognize');

const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
fs.mkdirSync(DATA_DIR, { recursive: true });
fs.mkdirSync(path.join(DATA_DIR, 'uploads'), { recursive: true });
const db = new DatabaseSync(path.join(DATA_DIR, 'medbox.db'));
db.exec('PRAGMA journal_mode = WAL');
// 旧库兼容：补 image_url 列
try { db.exec('ALTER TABLE medicines ADD COLUMN image_url TEXT DEFAULT \'\''); } catch (e) {}
try { db.exec('ALTER TABLE medicines ADD COLUMN barcode TEXT DEFAULT \'\''); } catch (e) {}
try { db.exec('ALTER TABLE medicines ADD COLUMN manufacturer TEXT DEFAULT \'\''); } catch (e) {}
try { db.exec('ALTER TABLE medicines ADD COLUMN approval_no TEXT DEFAULT \'\''); } catch (e) {}

// 设置表（键值对）
db.exec(`CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT
)`);

// 启动时加载 AI 配置
try {
  const row = db.prepare('SELECT value FROM settings WHERE key = ?').get('ai_config');
  if (row) ai.setRuntimeConfig(JSON.parse(row.value));
} catch (e) {}

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
CREATE TABLE IF NOT EXISTS drug_catalog (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  barcode TEXT DEFAULT '',
  approval_no TEXT DEFAULT '',
  name TEXT NOT NULL,
  common_name TEXT DEFAULT '',
  spec TEXT DEFAULT '',
  manufacturer TEXT DEFAULT '',
  dosage_form TEXT DEFAULT '',
  category TEXT DEFAULT '',
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_drug_barcode ON drug_catalog(barcode);
CREATE INDEX IF NOT EXISTS idx_drug_approval ON drug_catalog(approval_no);
CREATE INDEX IF NOT EXISTS idx_drug_name ON drug_catalog(name);
CREATE TABLE IF NOT EXISTS medicines (
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
  barcode TEXT DEFAULT '',
  manufacturer TEXT DEFAULT '',
  approval_no TEXT DEFAULT '',
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
// 开药提醒：库存 ÷ 日用量 = 还能服几天，据此推算下次开药日期
function refillInfo(m) {
  if (!m.daily_dose || m.daily_dose <= 0 || m.stock_qty == null) return null;
  const days = Math.floor(m.stock_qty / m.daily_dose);
  const d = new Date();
  d.setDate(d.getDate() + days);
  const p = n => String(n).padStart(2, '0');
  const nextRefillDate = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  return { daysLeft: days, needRefill: days <= 7, nextRefillDate };
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
app.use(express.json({ limit: '100mb' }));
app.use(express.urlencoded({ extended: true }));
// 兼容部分 HTTP 客户端（如安卓 HttpURLConnection）无法直接发送 PATCH：POST + X-HTTP-Method-Override 头转换
app.use((req, res, next) => {
  const ov = req.headers['x-http-method-override'];
  if (ov && req.method === 'POST') req.method = String(ov).toUpperCase();
  next();
});
app.use(express.static(path.join(__dirname, 'public')));
// 后台快捷地址
app.get('/admin', (req, res) => res.redirect('/admin.html'));
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
  const meds = getUserMedicines(uid).map(m => ({ ...m, refill: refillInfo(m) }));
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
    // 用本地药品库（药监局本位码数据集）按名称补全 AI 未识别出的字段
    if (type === 'prescription' && Array.isArray(r.meds)) {
      // 处方返回的是药品数组，顶层没有 name/spec，必须逐条补全
      r.meds = r.meds.map(m => enrichFromCatalog({ ...m }));
    } else {
      enrichFromCatalog(r);
    }
    res.json(r);
  } catch (e) { bad(res, e.message || 'AI识别失败'); }
});

// 匹配本地药品库，补全规格/厂家/批准文号（不覆盖 AI 已识别内容）
// 匹配优先级：批准文号精确匹配（唯一权威键，走索引）> 名称精确 > 名称前缀 > 反向前缀
function enrichFromCatalog(r) {
  if (!r) return r;
  // 规范化批准文号字段（AI 返回 approval_number）
  const apn = String(r.approval_no || r.approval_number || '').trim().toUpperCase().replace(/\s/g, '');
  if (apn) r.approval_no = apn;
  let hit = null;
  try {
    if (apn) hit = db.prepare('SELECT * FROM drug_catalog WHERE approval_no=? LIMIT 1').get(apn);
    if (!hit && r.name) {
      const q = String(r.name).trim();
      if (q.length >= 2) {
        hit = db.prepare("SELECT * FROM drug_catalog WHERE name=? AND spec!='' LIMIT 1").get(q);
        if (!hit) hit = db.prepare("SELECT * FROM drug_catalog WHERE name LIKE ? AND spec!='' LIMIT 1").get(q + '%');
        if (!hit) hit = db.prepare("SELECT * FROM drug_catalog WHERE ? LIKE name || '%' AND spec!='' LIMIT 1").get(q);
      }
    }
  } catch (e) { return r; }
  if (!hit) return r;
  if (!r.name && hit.name) r.name = hit.name;
  if (!r.spec && hit.spec) r.spec = hit.spec;
  if (!r.manufacturer && hit.manufacturer) r.manufacturer = hit.manufacturer;
  if (!r.approval_no && hit.approval_no) r.approval_no = hit.approval_no;
  if (!r.raw) r.raw = hit.name + '（' + hit.approval_no + '）';
  r.catalog_matched = true;
  return r;
}

// --- 药品条码（EAN-13等）查询 ---
// 查询顺序：1) 本家庭已录入的相同条码药品  2) 后台配置的自定义接口（可选）  3) UPCItemDB（免费无key）  4) OpenProductsFacts（免费无key）
function httpGetJson(url, headers = {}, timeoutMs = 8000) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const lib = u.protocol === 'http:' ? require('http') : https;
    const rq = lib.request({ hostname: u.hostname, port: u.port, path: u.pathname + u.search, method: 'GET',
      headers: { 'Accept': 'application/json', 'User-Agent': 'FamilyMedbox/1.0', ...headers } }, (resp) => {
      if (resp.statusCode >= 300 && resp.statusCode < 400 && resp.headers.location) {
        // 跟随一次重定向
        const loc = new URL(resp.headers.location, u).toString();
        resp.resume();
        httpGetJson(loc, headers, timeoutMs).then(resolve, reject);
        return;
      }
      let body = ''; resp.on('data', d => body += d);
      resp.on('end', () => { try { resolve(JSON.parse(body)); } catch (e) { reject(new Error('响应不是有效JSON')); } });
    });
    rq.on('error', reject); rq.setTimeout(timeoutMs, () => rq.destroy(new Error('查询超时'))); rq.end();
  });
}

// 各在线条码源解析为统一结构 {name, spec, manufacturer, raw_title}
function parseUpcItemDb(d) {
  if (!d || d.code !== 'OK' || !Array.isArray(d.items) || !d.items.length) return null;
  const it = d.items[0];
  const title = (it.title || '').trim();
  const specM = title.match(/(\d+(?:\.\d+)?\s*(?:mg|g|ml|μg|ug|IU)\s*[×xX\*]\s*\d+\s*[片粒贴袋支瓶丸枚袋]?|\d+(?:\.\d+)?\s*(?:mg|g|ml|μg|ug|IU))/i);
  let name = title, spec = '';
  if (specM) { spec = specM[1].replace(/\s+/g, ''); name = title.replace(specM[0], '').replace(/[|｜·\-—_\s]+$/g, '').trim(); }
  return { name, spec, manufacturer: it.manufacturer || it.brand || '', raw_title: title, source: 'UPCItemDB' };
}
function parseOpenProductsFacts(d) {
  if (!d || d.status !== 1 || !d.product) return null;
  const p = d.product;
  return {
    name: (p.product_name || '').trim(),
    spec: (p.quantity || '').trim(),
    manufacturer: (p.brands || '').trim(),
    raw_title: [p.product_name, p.quantity].filter(Boolean).join(' '),
    source: 'OpenProductsFacts'
  };
}

app.get('/api/medicine/barcode/:code', requireAuth, async (req, res) => {
  const code = (req.params.code || '').trim();
  if (!/^\d{8,14}$/.test(code)) return bad(res, '条码格式不正确（应为8-14位数字）');
  // 1) 本地家庭药品库：信息最全（含日用量、照片），直接整体复用
  const local = db.prepare('SELECT * FROM medicines WHERE family_id=? AND barcode=? ORDER BY id DESC LIMIT 1')
    .get(req.user.family_id, code);
  if (local) return res.json({ found: 'local', code, medicine: local });
  // 1.5) 本地药品基础库（药监局数据集导入）：条码→药品基础信息
  const catalog = db.prepare('SELECT * FROM drug_catalog WHERE barcode=? LIMIT 1').get(code);
  if (catalog) return res.json({ found: 'online', code, data: {
    name: catalog.name, spec: catalog.spec, manufacturer: catalog.manufacturer,
    approval_no: catalog.approval_no, raw_title: catalog.name, source: '本地药品库'
  }});
  // 2) 后台自定义查询接口（可选，模板 {code} 占位）
  let customUrl = '';
  try { const r = db.prepare('SELECT value FROM settings WHERE key=?').get('barcode_lookup_url'); if (r) customUrl = JSON.parse(r.value).url || ''; } catch (e) {}
  const tried = [];
  if (customUrl) {
    try {
      const d = await httpGetJson(customUrl.replace('{code}', encodeURIComponent(code)));
      const it = d && (d.items && d.items[0] ? d.items[0] : (d.data || d.product || d));
      const name = (it && (it.name || it.product_name || it.title || it.goodsName || it.药品名称)) || '';
      if (name) {
        return res.json({ found: 'online', code, data: {
          name: String(name), spec: String(it.spec || it.specification || it.specName || ''),
          manufacturer: String(it.manufacturer || it.producer || it.vendor || ''),
          raw_title: String(name), source: '自定义接口' } });
      }
      tried.push('自定义接口无数据');
    } catch (e) { tried.push('自定义接口失败'); }
  }
  // 3) UPCItemDB（国内服务器可能不可达，失败自动跳过）
  try {
    const d = await httpGetJson(`https://api.upcitemdb.com/prod/trial/lookup?upc=${encodeURIComponent(code)}`);
    const parsed = parseUpcItemDb(d);
    if (parsed) return res.json({ found: 'online', code, data: parsed });
    tried.push('UPCItemDB无数据');
  } catch (e) { tried.push('UPCItemDB不可达'); }
  // 4) OpenProductsFacts
  try {
    const d = await httpGetJson(`https://world.openproductsfacts.org/api/v0/product/${encodeURIComponent(code)}.json`);
    const parsed = parseOpenProductsFacts(d);
    if (parsed && parsed.name) return res.json({ found: 'online', code, data: parsed });
    tried.push('OpenProductsFacts无数据');
  } catch (e) { tried.push('OpenProductsFacts不可达'); }
  let msg = '各公开条码库均未收录（' + tried.join('，') + '）';
  if (code.length === 13 && code.startsWith('69')) msg += '。69开头的商品条码在国内没有免费公开查询库（GS1中国需授权），建议用「拍照录入」AI识别药盒名称，保存后全家扫这个码即可秒出全部信息';
  else if (code.length === 14 && code.startsWith('86')) msg += '。该本位码未收录，可核对包装上的条码数字后在添加页手动输入查询';
  res.json({ found: false, code, message: msg });
});

app.post('/api/medicines', requireAuth, (req, res) => {
  const { name, spec, category, expiry_date, stock_qty, unit, daily_dose, dose_unit, location, notes, image_url, barcode, manufacturer, approval_no } = req.body;
  if (!name) return bad(res, '药品名称必填');
  let uid = req.user.id;
  if (req.body.user_id && req.user.role === 'admin') uid = +req.body.user_id;
  const info = db.prepare(`INSERT INTO medicines(family_id,user_id,name,spec,category,expiry_date,stock_qty,unit,daily_dose,dose_unit,location,notes,image_url,barcode,manufacturer,approval_no)
    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`)
    .run(req.user.family_id, uid, name, spec || '', category || '', expiry_date || '', +stock_qty || 0, unit || '盒', +daily_dose || 0, dose_unit || '片', location || '', notes || '', image_url || '', barcode || '', manufacturer || '', approval_no || '');
  res.json({ id: Number(info.lastInsertRowid) });
});

// ---------- 处方批量入库 ----------
// 把处方上的「24盒 ×(100mg*14片)」换算为可用最小单位总量，日用量=每次剂量×每日次数
const DEFAULT_TIMES = {
  1: ['08:00'],
  2: ['08:00', '20:00'],
  3: ['08:00', '12:00', '20:00'],
  4: ['08:00', '12:00', '18:00', '22:00']
};
// 根据药名/规格推断最小剂量单位
function unitFromName(name, spec) {
  const s = `${name || ''}${spec || ''}`;
  if (/胶囊/.test(s)) return '粒';
  if (/颗粒|冲剂/.test(s)) return '袋';
  if (/丸/.test(s)) return '丸';
  if (/滴剂|口服液|糖浆|合剂|滴眼液|滴鼻液|滴耳液|ml|毫升/i.test(s)) return 'ml';
  if (/注射液|注射用|冻干/.test(s)) return '支';
  if (/栓/.test(s)) return '枚';
  if (/贴/.test(s)) return '贴';
  if (/乳膏|软膏|凝胶|气雾剂|喷雾剂/.test(s)) return '支';
  if (/片/.test(s)) return '片';
  return '片';
}
// 从任意字段提取数字（兼容 "24盒"、"每日3次"、"三次" 等字符串）
function num(v) {
  if (v == null) return 0;
  if (typeof v === 'number') return isFinite(v) ? v : 0;
  const s = String(v);
  const m = s.match(/\d+(?:\.\d+)?/);
  if (m) return +m[0];
  const cn = { '一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5, '六': 6 };
  for (const k in cn) if (s.includes(k)) return cn[k];
  return 0;
}
// 解析规格中的每包装最小单位数量：100mg*14片→14片；5g*18→18袋；30ml→30ml
// 无法解析时返回 null（不做虚假换算）
function parsePack(spec, fbUnit) {
  const s = String(spec || '').replace(/\s/g, '');
  if (!s) return null;
  let m = s.match(/\*\s*(\d+(?:\.\d+)?)(片|粒|袋|支|丸|枚|贴|瓶)?/i);
  if (m) return { perPack: +m[1], unit: m[2] || fbUnit || '片' };
  m = s.match(/(\d+(?:\.\d+)?)(片|粒|袋|支|丸|枚|贴|瓶)/);
  if (m) return { perPack: +m[1], unit: m[2] };
  m = s.match(/(\d+(?:\.\d+)?)(ml|毫升)/i);
  if (m) return { perPack: +m[1], unit: 'ml' };
  return null;
}
// 解析规格中的单最小单位含量：100mg*14片→{value:100,unit:'mg'}
function parseStrength(spec) {
  const m = String(spec || '').match(/(\d+(?:\.\d+)?)\s*(mg|g|μg|ug|ml)/i);
  if (!m) return null;
  let v = +m[1], u = m[2].toLowerCase();
  if (u === 'g') { v *= 1000; u = 'mg'; }
  if (u === 'ug' || u === 'μg') { v /= 1000; u = 'mg'; }
  return { value: v, unit: u };
}

app.post('/api/prescriptions/import', requireAuth, (req, res) => {
  const items = Array.isArray(req.body.meds) ? req.body.meds : [];
  if (!items.length) return bad(res, '未识别到药品');
  let uid = req.user.id;
  if (req.body.user_id && req.user.role === 'admin') uid = +req.body.user_id;
  const startDate = req.body.start_date || todayStr();
  const createPlan = req.body.create_schedule !== false;
  const created = [];
  db.exec('BEGIN');
  try {
    for (const it of items) {
      // 名称中若带规格括注（如「XX片（100mg*14片）」），拆出规格
      let name = String(it.name || '').trim();
      let spec = String(it.spec || '').trim();
      const pm = name.match(/[（(]([^）)]+)[）)]/);
      if (pm) {
        if (!spec) spec = pm[1].trim();
        name = name.replace(/[（(][^）)]*[）)]/g, '').trim();
      }
      if (!name) continue;
      const fbUnit = unitFromName(name, spec);
      const packQty = num(it.pack_qty) || num(it.stock_qty);
      const packUnit = String(it.pack_unit || '盒').trim();
      // 用权威库补全厂家/批准文号；未收录也不影响，直接按处方名称入库
      let extra = {};
      try { extra = enrichFromCatalog({ name, spec }) || {}; } catch (e) { extra = {}; }
      // 处方没写规格时，尝试用权威库规格换算
      let pack = parsePack(spec, fbUnit);
      if (!pack && extra.spec) pack = parsePack(extra.spec, fbUnit);
      const inCatalog = extra.catalog_matched === true;

      let totalStock, stockUnit, doseUnit, dailyDose = 0;
      let dosePerTime = num(it.dose_per_time);
      doseUnit = String(it.dose_unit || '').trim() || (pack ? pack.unit : fbUnit);
      if (pack) {
        // 规格可解析：库存换算为最小单位（片/粒/袋/ml）
        totalStock = packQty > 0 ? +(packQty * pack.perPack).toFixed(2) : 0;
        stockUnit = pack.unit;
        // 每次剂量换算成最小单位（如 400mg ÷ 100mg/片 = 4片）
        const st = parseStrength(spec || extra.spec || '');
        if (dosePerTime > 0 && st && doseUnit.toLowerCase() === st.unit && doseUnit.toLowerCase() !== pack.unit.toLowerCase()) {
          dosePerTime = +(dosePerTime / st.value).toFixed(2);
          doseUnit = pack.unit;
        }
        dailyDose = +(dosePerTime * Math.max(0, num(it.times_per_day))).toFixed(2);
      } else {
        // 规格缺失/无法解析：按包装数量入库，不换算、不预估开药时间
        totalStock = packQty;
        stockUnit = packUnit;
      }
      const timesPerDay = Math.max(0, num(it.times_per_day));
      const durationDays = Math.max(0, num(it.duration_days));
      // 备注：记录处方原始数量，未收录/未换算的情况明确标注
      const noteParts = [];
      if (packQty > 0) noteParts.push(`处方导入：${packQty}${packUnit}${spec ? ' × ' + spec : ''}`);
      else noteParts.push('处方导入');
      if (!inCatalog) noteParts.push('基础库未收录，按处方名称录入');
      if (!pack) noteParts.push('规格未识别，请补充规格后启用开药提醒');
      const notes = noteParts.join('；');
      // 同一成员已有同名同单位的药品 → 累加库存，避免重复扫同一张处方产生多条记录
      const exist = db.prepare('SELECT * FROM medicines WHERE user_id=? AND name=? AND unit=? ORDER BY id DESC LIMIT 1')
        .get(uid, name, stockUnit);
      let medId, merged = false, finalStock = totalStock;
      if (exist) {
        merged = true;
        medId = exist.id;
        const stock = +(Number(exist.stock_qty || 0) + (totalStock > 0 ? totalStock : 0)).toFixed(2);
        finalStock = stock;
        const daily = dailyDose > 0 ? dailyDose : Number(exist.daily_dose || 0);
        const du = dailyDose > 0 ? doseUnit : (exist.dose_unit || doseUnit);
        const allNotes = [exist.notes, `${startDate} 处方再次入库${packQty > 0 ? ` +${packQty}${packUnit}` : ''}`]
          .filter(Boolean).join('；');
        db.prepare(`UPDATE medicines SET stock_qty=?, daily_dose=?, dose_unit=?, spec=?, notes=?, manufacturer=?, approval_no=? WHERE id=?`)
          .run(stock, daily, du, spec || exist.spec || '', allNotes,
            exist.manufacturer || extra.manufacturer || '',
            exist.approval_no || extra.approval_no || '', medId);
      } else {
        const info = db.prepare(`INSERT INTO medicines(family_id,user_id,name,spec,stock_qty,unit,daily_dose,dose_unit,notes,manufacturer,approval_no)
          VALUES(?,?,?,?,?,?,?,?,?,?,?)`)
          .run(req.user.family_id, uid, name, spec, totalStock, stockUnit,
            dailyDose, doseUnit, notes, extra.manufacturer || '', extra.approval_no || '');
        medId = Number(info.lastInsertRowid);
      }
      // 按用法用量自动建用药计划（规格无法换算时不建，避免剂量错误）
      let planCreated = false;
      if (createPlan && pack && timesPerDay > 0 && dosePerTime > 0) {
        const times = DEFAULT_TIMES[Math.min(4, Math.max(1, Math.round(timesPerDay)))] || DEFAULT_TIMES[4];
        let endDate = '';
        if (durationDays > 0) {
          const d = new Date(startDate + 'T00:00:00');
          d.setDate(d.getDate() + durationDays - 1);
          const p = n => String(n).padStart(2, '0');
          endDate = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
        }
        // 合并时若已有完全相同的计划，不重复创建
        const dup = db.prepare('SELECT id FROM schedules WHERE medicine_id=? AND enabled=1 AND times=? AND dose_per_time=?')
          .get(medId, JSON.stringify(times), dosePerTime);
        if (!dup) {
          db.prepare(`INSERT INTO schedules(family_id,medicine_id,user_id,times,dose_per_time,dose_unit,frequency,interval_days,start_date,end_date)
            VALUES(?,?,?,?,?,?,?,?,?,?)`)
            .run(req.user.family_id, medId, uid, JSON.stringify(times), dosePerTime, doseUnit, 'daily', 1, startDate, endDate);
          planCreated = true;
        }
      }
      created.push({ id: medId, name, spec, stock_qty: finalStock, unit: stockUnit, daily_dose: dailyDose, dose_unit: doseUnit, times_per_day: timesPerDay, duration_days: durationDays, in_catalog: inCatalog, converted: !!pack, merged, plan_created: planCreated });
    }
    db.exec('COMMIT');
  } catch (e) {
    db.exec('ROLLBACK');
    return res.status(500).json({ error: '入库失败：' + e.message });
  }
  res.json({ ok: true, count: created.length, meds: created });
});

app.patch('/api/medicines/:id', requireAuth, (req, res) => {
  const m = db.prepare('SELECT * FROM medicines WHERE id=?').get(req.params.id);
  if (!m || m.family_id !== req.user.family_id) return bad(res, '药品不存在');
  if (m.user_id !== req.user.id && req.user.role !== 'admin') return bad(res, '无权限');
  const f = ['name', 'spec', 'category', 'expiry_date', 'stock_qty', 'unit', 'daily_dose', 'dose_unit', 'location', 'notes', 'image_url', 'barcode', 'manufacturer', 'approval_no'];
  const sets = [], vals = [];
  for (const k of f) if (req.body[k] !== undefined) { sets.push(`${k}=?`); vals.push(req.body[k]); }
  if (sets.length) db.prepare(`UPDATE medicines SET ${sets.join(',')} WHERE id=?`).run(...vals, m.id);
  res.json({ ok: true });
});

// 补货：输入新买的盒数，按规格换算后累加到库存，并返回新的开药日期
app.post('/api/medicines/:id/refill', requireAuth, (req, res) => {
  const m = db.prepare('SELECT * FROM medicines WHERE id=?').get(req.params.id);
  if (!m || m.family_id !== req.user.family_id) return bad(res, '药品不存在');
  if (m.user_id !== req.user.id && req.user.role !== 'admin') return bad(res, '无权限');
  const packQty = num(req.body.pack_qty);
  if (packQty <= 0) return bad(res, '请输入补货数量');
  const pack = parsePack(m.spec, unitFromName(m.name, m.spec));
  let stock, unit;
  if (pack) {
    // 统一按最小单位（片/粒/袋/ml）累加；历史上按盒记的库存一并折算
    const base = m.unit && m.unit !== pack.unit ? +(m.stock_qty * pack.perPack).toFixed(2) : +m.stock_qty || 0;
    stock = +(base + packQty * pack.perPack).toFixed(2);
    unit = pack.unit;
  } else {
    // 规格无法解析：按当前单位原样累加
    stock = +(Number(m.stock_qty || 0) + packQty).toFixed(2);
    unit = m.unit || '盒';
  }
  db.prepare('UPDATE medicines SET stock_qty=?, unit=? WHERE id=?').run(stock, unit, m.id);
  res.json({ ok: true, stock_qty: stock, unit, refill: refillInfo({ ...m, stock_qty: stock, unit }) });
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
  // 服药后扣减库存；改回「跳过/未服」时退回，避免重复点击造成库存错乱
  if (st === 'taken' && (!log || log.status !== 'taken')) {
    db.prepare('UPDATE medicines SET stock_qty = MAX(0, stock_qty - ?) WHERE id=?').run(sc.dose_per_time, sc.medicine_id);
  } else if (st !== 'taken' && log && log.status === 'taken') {
    db.prepare('UPDATE medicines SET stock_qty = stock_qty + ? WHERE id=?').run(sc.dose_per_time, sc.medicine_id);
  }
  res.json({ ok: true });
});

// --- 预警：按用法用量推算的开药提醒（不再提醒药品过期） ---
app.get('/api/alerts', requireAuth, (req, res) => {
  let uid = req.query.user_id && req.user.role === 'admin' ? +req.query.user_id : null;
  const meds = uid
    ? getUserMedicines(uid)
    : db.prepare('SELECT * FROM medicines WHERE family_id=?').all(req.user.family_id);
  const refill = [], upcoming = [];
  for (const m of meds) {
    const owner = db.prepare('SELECT display_name FROM users WHERE id=?').get(m.user_id);
    const r = refillInfo(m);
    if (!r) continue;
    const item = { ...m, owner: owner?.display_name, days_left: r.daysLeft, next_refill_date: r.nextRefillDate };
    upcoming.push(item);
    if (r.needRefill) refill.push(item);
  }
  upcoming.sort((a, b) => a.days_left - b.days_left);
  res.json({ refill, upcoming });
});

// --- 管理员总览 ---
app.get('/api/admin/overview', requireAuth, requireAdmin, (req, res) => {
  const members = db.prepare('SELECT id,username,display_name,role,age,notes,created_at FROM users WHERE family_id=? ORDER BY id').all(req.user.family_id);
  const today = todayStr();
  const result = members.map(m => {
    const meds = getUserMedicines(m.id).map(md => ({ ...md, refill: refillInfo(md) }));
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
  const alerts = { refill: [] };
  for (const mem of result) {
    for (const md of mem.medicines) {
      if (md.refill && md.refill.needRefill) alerts.refill.push({ medicine_id: md.id, name: md.name, owner: mem.display_name, days_left: md.refill.daysLeft, next_refill_date: md.refill.nextRefillDate, stock_qty: md.stock_qty, unit: md.unit, daily_dose: md.daily_dose });
    }
  }
  alerts.refill.sort((a, b) => a.days_left - b.days_left);
  res.json({ members: result, alerts });
});

// --- 本地药品基础库（药监局数据集） ---
// 导入：CSV（每行一条，列：条码,批准文号,商品名,通用名,规格,生产企业,剂型,分类）或 JSON 数组
app.post('/api/admin/drug-catalog/import', requireAuth, requireAdmin, (req, res) => {
  const { format, data } = req.body || {};
  if (!data) return bad(res, '无数据');
  let rows = [];
  try {
    if (format === 'csv') {
      const lines = String(data).split(/\r?\n/).map(l => l.trim()).filter(l => l && !l.startsWith('#'));
      for (const line of lines) {
        const c = line.split(/,|\t/).map(s => s.trim().replace(/^"|"$/g, ''));
        if (c.length >= 3) rows.push({ barcode: c[0] || '', approval_no: c[1] || '', name: c[2] || '', common_name: c[3] || '', spec: c[4] || '', manufacturer: c[5] || '', dosage_form: c[6] || '', category: c[7] || '' });
      }
    } else if (format === 'json') {
      const arr = typeof data === 'string' ? JSON.parse(data) : data;
      rows = (Array.isArray(arr) ? arr : []).map(o => ({
        barcode: String(o.barcode || o.code || '').trim(),
        approval_no: String(o.approval_no || o.approvalNo || o.批准文号 || '').trim(),
        name: String(o.name || o.商品名 || o.name_zh || '').trim(),
        common_name: String(o.common_name || o.commonName || o.通用名 || '').trim(),
        spec: String(o.spec || o.specification || o.规格 || '').trim(),
        manufacturer: String(o.manufacturer || o.producer || o.生产企业 || '').trim(),
        dosage_form: String(o.dosage_form || o.dosageForm || o.剂型 || '').trim(),
        category: String(o.category || o.分类 || '').trim()
      })).filter(r => r.name);
    }
  } catch (e) { return bad(res, '数据解析失败：' + e.message); }
  if (!rows.length) return bad(res, '未解析到有效数据（CSV需至少3列：条码,批准文号,商品名）');
  const stmt = db.prepare('INSERT INTO drug_catalog(barcode,approval_no,name,common_name,spec,manufacturer,dosage_form,category) VALUES(?,?,?,?,?,?,?,?)');
  let n = 0;
  db.exec('BEGIN');
  try {
    for (const r of rows) { stmt.run(r.barcode, r.approval_no, r.name, r.common_name, r.spec, r.manufacturer, r.dosage_form, r.category); n++; }
    db.exec('COMMIT');
  } catch (e) { db.exec('ROLLBACK'); return bad(res, '导入失败：' + e.message); }
  const total = db.prepare('SELECT COUNT(*) c FROM drug_catalog').get().c;
  res.json({ imported: n, total });
});

// 清空本地药品库
app.post('/api/admin/drug-catalog/clear', requireAuth, requireAdmin, (req, res) => {
  db.exec('DELETE FROM drug_catalog');
  res.json({ ok: true });
});

// 本地药品库统计
app.get('/api/admin/drug-catalog/stats', requireAuth, requireAdmin, (req, res) => {
  const total = db.prepare('SELECT COUNT(*) c FROM drug_catalog').get().c;
  const withBarcode = db.prepare("SELECT COUNT(*) c FROM drug_catalog WHERE barcode != ''").get().c;
  res.json({ total, withBarcode });
});

// --- AI 模型配置（管理员） ---
app.get('/api/admin/ai-config', requireAuth, requireAdmin, (req, res) => {
  const cfg = ai.getCfg();
  let barcodeLookupUrl = '';
  try { const r = db.prepare('SELECT value FROM settings WHERE key=?').get('barcode_lookup_url'); if (r) barcodeLookupUrl = JSON.parse(r.value).url || ''; } catch (e) {}
  const masked = {
    provider: cfg.provider || '',
    model: cfg.model || '',
    baseUrl: cfg.baseUrl || '',
    apiKey: cfg.apiKey ? '****' : '',
    baiduAk: cfg.baiduAk ? '****' : '',
    baiduSk: cfg.baiduSk ? '****' : '',
    fromEnv: !ai.runtimeSet,
    barcodeLookupUrl
  };
  res.json(masked);
});

app.post('/api/admin/ai-config', requireAuth, requireAdmin, (req, res) => {
  const { provider, apiKey, baseUrl, model, baiduAk, baiduSk, barcodeLookupUrl } = req.body || {};
  const cur = ai.getCfg();
  const cfg = {
    provider: (provider || '').toLowerCase(),
    apiKey: apiKey === '****' ? (cur.apiKey || '') : (apiKey || ''),
    baseUrl: baseUrl || '',
    model: model || '',
    baiduAk: baiduAk === '****' ? (cur.baiduAk || '') : (baiduAk || ''),
    baiduSk: baiduSk === '****' ? (cur.baiduSk || '') : (baiduSk || '')
  };
  db.prepare('INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value')
    .run('ai_config', JSON.stringify(cfg));
  if (barcodeLookupUrl !== undefined) {
    db.prepare('INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value')
      .run('barcode_lookup_url', JSON.stringify({ url: barcodeLookupUrl || '' }));
  }
  ai.setRuntimeConfig(cfg);
  res.json({ ok: true, provider: cfg.provider });
});

// --- AI 连通性测试 ---
app.post('/api/admin/ai-test', requireAuth, requireAdmin, async (req, res) => {
  try {
    const result = await ai.testConnection();
    res.json(result);
  } catch (e) {
    res.json({ ok: false, error: e.message || String(e) });
  }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, '0.0.0.0', () => console.log(`家庭用药服务已启动: http://0.0.0.0:${PORT}  (后台: /admin.html)`));
