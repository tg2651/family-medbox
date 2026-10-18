/**
 * AI 视觉识别（药品包装 / 处方）—— 服务端可配置模型
 *
 * 通过环境变量配置，支持两种 provider：
 *
 * 1) openai —— OpenAI 兼容接口（推荐，国内通义千问 VL / DeepSeek-VL / 智谱 GLM-4V / Kimi 均支持）
 *    AI_PROVIDER=openai
 *    AI_API_KEY=你的APIKey
 *    AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1   # 通义千问兼容端点
 *    AI_MODEL=qwen-vl-plus                                           # 模型名
 *
 * 2) baidu —— 百度文心 ERNIE-4.0 多模态
 *    AI_PROVIDER=baidu
 *    BAIDU_API_KEY=xxx
 *    BAIDU_SECRET_KEY=xxx
 *    AI_MODEL=ernie-4.0-8k-preview   # 可选，默认 ernie-4.0-8k-preview
 *
 * 未配置任何 provider 时接口返回 "AI 未配置"，不影响 APP 其他功能。
 */
const https = require('https');
const http = require('http');
const { URL } = require('url');

const provider = (process.env.AI_PROVIDER || '').toLowerCase();

// 运行时配置（由管理后台写入数据库后加载，优先级高于环境变量）
let runtimeConfig = null;
let runtimeSet = false;
function getCfg() {
  if (runtimeConfig) return runtimeConfig;
  return {
    provider: (process.env.AI_PROVIDER || '').toLowerCase(),
    apiKey: process.env.AI_API_KEY || '',
    baseUrl: process.env.AI_BASE_URL || '',
    model: process.env.AI_MODEL || '',
    baiduAk: process.env.BAIDU_API_KEY || '',
    baiduSk: process.env.BAIDU_SECRET_KEY || ''
  };
}
function setRuntimeConfig(cfg) { runtimeConfig = cfg; runtimeSet = true; baiduToken = ''; baiduTokenExpire = 0; }

/* ---------------- 百度文心 ---------------- */
let baiduToken = '';
let baiduTokenExpire = 0;

function baiduAccessToken() {
  return new Promise((resolve, reject) => {
    const cfg = getCfg();
    const ak = cfg.baiduAk;
    const sk = cfg.baiduSk;
    if (!ak || !sk) return reject(new Error('未配置 BAIDU_API_KEY / BAIDU_SECRET_KEY'));
    if (baiduToken && Date.now() < baiduTokenExpire) return resolve(baiduToken);
    const url = `/oauth/2.0/token?grant_type=client_credentials&client_id=${encodeURIComponent(ak)}&client_secret=${encodeURIComponent(sk)}`;
    const req = https.request(
      { hostname: 'aip.baidubce.com', path: url, method: 'POST' },
      (res) => {
        let body = '';
        res.on('data', d => body += d);
        res.on('end', () => {
          try {
            const j = JSON.parse(body);
            if (j.access_token) {
              baiduToken = j.access_token;
              baiduTokenExpire = Date.now() + (j.expires_in - 600) * 1000;
              resolve(baiduToken);
            } else reject(new Error('百度鉴权失败：' + (j.error_description || body)));
          } catch (e) { reject(new Error('百度鉴权响应解析失败')); }
        });
      }
    );
    req.on('error', reject);
    req.end();
  });
}

function callBaidu(imageBase64, systemPrompt, userPrompt) {
  return new Promise(async (resolve, reject) => {
    try {
      const cfg = getCfg();
      const token = await baiduAccessToken();
      const model = cfg.model || 'ernie-4.0-8k-preview';
      const url = `/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/${model}?access_token=${token}`;
      const payload = JSON.stringify({
        system: systemPrompt,
        messages: [{ role: 'user', content: [
          { type: 'text', text: userPrompt },
          { type: 'image_url', image_url: { url: `data:image/jpeg;base64,${imageBase64}` } }
        ]}]
      });
      const req = https.request(
        { hostname: 'aip.baidubce.com', path: url, method: 'POST',
          headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) } },
        (res) => {
          let body = '';
          res.on('data', d => body += d);
          res.on('end', () => {
            try {
              const j = JSON.parse(body);
              if (j.result) resolve(j.result);
              else reject(new Error('文心识别失败：' + (j.error_msg || body)));
            } catch (e) { reject(new Error('文心响应解析失败')); }
          });
        }
      );
      req.on('error', reject);
      req.write(payload);
      req.end();
    } catch (e) { reject(e); }
  });
}

/* ---------------- OpenAI 兼容 ---------------- */
function callOpenAI(imageBase64, systemPrompt, userPrompt) {
  return new Promise((resolve, reject) => {
    const cfg = getCfg();
    const apiKey = cfg.apiKey;
    const baseUrl = cfg.baseUrl;
    const model = cfg.model;
    if (!apiKey || !baseUrl || !model) return reject(new Error('未配置 AI_API_KEY / AI_BASE_URL / AI_MODEL'));
    const payload = JSON.stringify({
      model,
      messages: [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: [
          { type: 'text', text: userPrompt },
          { type: 'image_url', image_url: { url: `data:image/jpeg;base64,${imageBase64}` } }
        ]}
      ],
      temperature: 0.2
    });
    const u = new URL(baseUrl.replace(/\/$/, '') + '/chat/completions');
    const lib = u.protocol === 'http:' ? http : https;
    const req = lib.request(
      { hostname: u.hostname, port: u.port || (u.protocol === 'http:' ? 80 : 443), path: u.pathname + u.search, method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${apiKey}`, 'Content-Length': Buffer.byteLength(payload) } },
      (res) => {
        let body = '';
        res.on('data', d => body += d);
        res.on('end', () => {
          try {
            const j = JSON.parse(body);
            const content = j.choices?.[0]?.message?.content;
            if (content) resolve(content);
            else reject(new Error('AI 返回异常：' + (j.error?.message || body)));
          } catch (e) { reject(new Error('AI 响应解析失败：' + body.slice(0, 200))); }
        });
      }
    );
    req.on('error', reject);
    req.write(payload);
    req.end();
  });
}

/* ---------------- 统一入口 ---------------- */
function callAI(imageBase64, systemPrompt, userPrompt) {
  const p = (getCfg().provider || '').toLowerCase();
  if (p === 'baidu') return callBaidu(imageBase64, systemPrompt, userPrompt);
  if (p === 'openai') return callOpenAI(imageBase64, systemPrompt, userPrompt);
  return Promise.reject(new Error('AI 未配置：请在管理后台「AI配置」中选择模型并填入密钥'));
}

function extractJson(text) {
  const m = text.match(/\{[\s\S]*\}/);
  if (!m) return null;
  try { return JSON.parse(m[0]); } catch (e) { return null; }
}

async function recognizeMedicine(imageBase64) {
  const system = '你是一名专业的药品信息识别助手。用户上传药品包装或说明书图片，请识别其中的药品信息并仅返回 JSON，不要输出任何多余文字。';
  const user = `请识别图片中的药品信息，仅返回 JSON，字段如下：
{
  "name": "药品名称（中文通用名，如 硝苯地平缓释片）",
  "spec": "规格，如 0.25g×24片 或 10mg*7片；无法识别留空",
  "manufacturer": "生产厂家；无法识别留空",
  "approval_number": "国药准字批准文号；无法识别留空",
  "expiry_date": "有效期至，格式 YYYY-MM-DD；无法识别留空",
  "indications": "适应症/功能主治，一句话；无法识别留空",
  "daily_dose_default": "成人常用每日总剂量（数字，单位见 dose_unit），无法识别填 0",
  "dose_unit": "剂量单位（片/粒/ml/mg 等），默认 片"
}
无法识别的字段留空或 0，不要编造。`;
  const text = await callAI(imageBase64, system, user);
  const j = extractJson(text) || {};
  return {
    name: j.name || '', spec: j.spec || '', manufacturer: j.manufacturer || '',
    approval_number: j.approval_number || '', expiry_date: j.expiry_date || '',
    indications: j.indications || '', daily_dose_default: Number(j.daily_dose_default) || 0,
    dose_unit: j.dose_unit || '片', raw: text
  };
}

async function recognizePrescription(imageBase64) {
  const system = '你是一名专业的处方识别助手。用户上传医生处方图片，请识别处方中的药品及用法用量，仅返回 JSON，不要输出多余文字。';
  const user = `请识别处方中的药品信息，仅返回 JSON，结构如下：
{
  "meds": [
    {
      "name": "药品名称",
      "dose_per_time": "每次剂量（纯数字，如 1）",
      "dose_unit": "剂量单位（片/粒/ml/mg）",
      "times_per_day": "每日服用次数（数字 1-4）",
      "duration_days": "用药天数（数字，未知填 0）"
    }
  ]
}
只识别处方中明确列出的药品，无法识别的字段留空或 0，不要编造。`;
  const text = await callAI(imageBase64, system, user);
  const j = extractJson(text) || {};
  const meds = Array.isArray(j.meds) ? j.meds : [];
  return {
    meds: meds.map(m => ({
      name: m.name || '', dose_per_time: m.dose_per_time || '',
      dose_unit: m.dose_unit || '片', times_per_day: Number(m.times_per_day) || 0,
      duration_days: Number(m.duration_days) || 0
    })),
    raw: text
  };
}

module.exports = { recognizeMedicine, recognizePrescription, isConfigured: () => !!(getCfg().provider), setRuntimeConfig, getCfg, get runtimeSet(){return runtimeSet} };
