import json
import re
from pathlib import Path

root = Path(r"c:\Users\26634\Desktop\Projects\GZHU_Seat_Booking")
src = root / "TEMP" / "libbooking.gzhu.edu.cn.har"
out = root / "CODE" / "research" / "auth" / "har" / "libbooking.sanitized.har"

sensitive_keys = {
    "password","pwd","rsa","lt","execution","ticket","unitoken","token",
    "castgc","jsessionid","cookie","cookieheader","idcard","cardno","cardid",
    "pid","logonname","accno","truename","authorization"
}

def scrub_text(s: str) -> str:
    s = re.sub(r'("pid"\s*:\s*")[^"]+(")', r"\1<ACCOUNT_ID>\2", s)
    s = re.sub(r'("logonName"\s*:\s*")[^"]+(")', r"\1<ACCOUNT_ID>\2", s)
    s = re.sub(r'("cardNo"\s*:\s*")[^"]+(")', r"\1<CARD_NO>\2", s)
    s = re.sub(r'("cardId"\s*:\s*")[^"]+(")', r"\1<CARD_ID>\2", s)
    s = re.sub(r'("trueName"\s*:\s*")[^"]+(")', r"\1<USER_NAME>\2", s)
    s = re.sub(r"TGT-[^\s\"';,]+", "<CASTGC_TOKEN>", s)
    s = re.sub(r"ST-[^\s\"'&,]+", "<CAS_TICKET>", s)
    s = re.sub(r"([?&](?:ticket|uniToken|token|access_token)=)[^&\s\"]+", r"\1<REDACTED>", s, flags=re.IGNORECASE)
    s = re.sub(r"(?i)(Bearer\s+)[A-Za-z0-9._\-]+", r"\1<REDACTED>", s)
    s = re.sub(r"\b1[3-9]\d{9}\b", "<PHONE>", s)
    s = re.sub(r"\b\d{11}\b", "<ID_11_DIGITS>", s)
    return s

def sanitize(obj):
    if isinstance(obj, dict):
        cleaned = {}
        for k, v in obj.items():
            lk = k.lower()
            if lk in sensitive_keys:
                cleaned[k] = "<REDACTED>"
                continue
            if lk == "headers" and isinstance(v, list):
                new_headers = []
                for item in v:
                    if isinstance(item, dict):
                        name = str(item.get("name", "")).lower()
                        if name in sensitive_keys or name in {"set-cookie", "x-auth-token"}:
                            item = dict(item)
                            item["value"] = "<REDACTED>"
                            new_headers.append(item)
                        else:
                            new_headers.append({ik: sanitize(iv) for ik, iv in item.items()})
                    else:
                        new_headers.append(sanitize(item))
                cleaned[k] = new_headers
                continue
            cleaned[k] = sanitize(v)
        return cleaned
    if isinstance(obj, list):
        return [sanitize(x) for x in obj]
    if isinstance(obj, str):
        return scrub_text(obj)
    return obj

raw = json.loads(src.read_text(encoding="utf-8"))
masked = sanitize(raw)
out.write_text(json.dumps(masked, ensure_ascii=False), encoding="utf-8")
print(out)
print(out.stat().st_size)
