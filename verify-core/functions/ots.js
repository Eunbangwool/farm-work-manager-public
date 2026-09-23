// OpenTimestamps 해시 앵커 — 순수 로직 (firebase 의존 없음 → 단위 테스트 가능).
// 설계: docs/EVIDENCE_ANCHORING.md Phase 1
// 외부에는 SHA-256 해시만 전송. 이미지·좌표 원본은 보내지 않는다.

const HASH_RE = /^[0-9a-fA-F]{64}$/;
const PROVIDER = "ots";
const MAX_ERROR = 200;

function codeFromHash(hashSha256) {
  return String(hashSha256 || "").slice(0, 12).toUpperCase();
}

/** @returns {string|null} 오류코드. null 이면 유효. */
function validateRequest(code, hashSha256) {
  if (!hashSha256 || !HASH_RE.test(hashSha256)) return "invalid-hash";
  if (!code || codeFromHash(hashSha256) !== String(code).toUpperCase()) return "code-mismatch";
  return null;
}

/**
 * OpenTimestamps.verify 결과에서 비트코인 블록 높이·유닉스시각을 뽑는다.
 * 라이브러리 버전에 따라 { bitcoin: { height, timestamp } } 또는 숫자만 올 수 있다.
 */
function parseVerifyResult(verifyResult) {
  if (verifyResult == null) return { height: null, timestamp: null };
  if (typeof verifyResult === "number") {
    return { height: null, timestamp: verifyResult };
  }
  if (typeof verifyResult !== "object") return { height: null, timestamp: null };
  const btc = verifyResult.bitcoin || verifyResult.BTC || null;
  if (typeof btc === "number") return { height: null, timestamp: btc };
  if (!btc || typeof btc !== "object") return { height: null, timestamp: null };
  const height = btc.height != null ? Number(btc.height) : null;
  let ts = btc.timestamp != null ? Number(btc.timestamp) : null;
  // 일부 구현은 Date 객체
  if (btc.timestamp instanceof Date) ts = Math.floor(btc.timestamp.getTime() / 1000);
  return {
    height: Number.isFinite(height) && height > 0 ? height : null,
    timestamp: Number.isFinite(ts) && ts > 0 ? ts : null,
  };
}

function toBase64(buf) {
  return Buffer.from(buf).toString("base64");
}

function fromBase64(s) {
  return Buffer.from(String(s || ""), "base64");
}

function loadOtsLib(injected) {
  if (injected) return injected;
  return require("opentimestamps");
}

async function stamp(hashSha256, otsLib) {
  const OpenTimestamps = loadOtsLib(otsLib);
  const hash = Buffer.from(hashSha256, "hex");
  const detached = OpenTimestamps.DetachedTimestampFile.fromHash(
    new OpenTimestamps.Ops.OpSHA256(),
    hash
  );
  await OpenTimestamps.stamp(detached);
  return Buffer.from(detached.serializeToBytes());
}

async function upgrade(otsBytes, otsLib) {
  const OpenTimestamps = loadOtsLib(otsLib);
  const detached = OpenTimestamps.DetachedTimestampFile.deserialize(Buffer.from(otsBytes));
  const changed = await OpenTimestamps.upgrade(detached);
  return { changed: !!changed, ots: Buffer.from(detached.serializeToBytes()) };
}

async function verify(hashSha256, otsBytes, otsLib) {
  const OpenTimestamps = loadOtsLib(otsLib);
  const hash = Buffer.from(hashSha256, "hex");
  const original = OpenTimestamps.DetachedTimestampFile.fromHash(
    new OpenTimestamps.Ops.OpSHA256(),
    hash
  );
  const detached = OpenTimestamps.DetachedTimestampFile.deserialize(Buffer.from(otsBytes));
  const result = await OpenTimestamps.verify(detached, original);
  return parseVerifyResult(result);
}

/** 이미 확정됐으면 재제출하지 않음. FAILED 만 재시도. */
function shouldRestamp(existing) {
  if (!existing) return true;
  const st = existing.status;
  if (st === "ANCHORED" || st === "PENDING") return false;
  return true;
}

function clientAnchorPayload(d, nowMillis) {
  const anchoredAt = d && d.anchoredAt && typeof d.anchoredAt.toMillis === "function"
    ? d.anchoredAt.toMillis()
    : (typeof (d && d.anchoredAt) === "number" ? d.anchoredAt : (nowMillis || 0));
  return {
    ok: true,
    status: (d && d.status) || "PENDING",
    otsProof: (d && d.otsProof) || "",
    otsUpgraded: !!(d && d.otsUpgraded),
    otsBtcBlock: (d && d.otsBtcBlock) || null,
    anchoredAt: anchoredAt || null,
  };
}

module.exports = {
  PROVIDER,
  HASH_RE,
  MAX_ERROR,
  codeFromHash,
  validateRequest,
  parseVerifyResult,
  toBase64,
  fromBase64,
  stamp,
  upgrade,
  verify,
  shouldRestamp,
  clientAnchorPayload,
};
