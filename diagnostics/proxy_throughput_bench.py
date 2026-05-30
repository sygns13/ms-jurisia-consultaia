"""
Mini-bench de throughput de SUBIDA hacia Vertex AI (aiplatform.googleapis.com),
para cuantificar el impacto del proxy del Poder Judicial en la calificación de demandas.

Contexto: la calificación sube el PDF (≈10 MB → ~13.3 MB en base64) DOS veces (Fase 1 + Fase 4).
Con proxy se midieron ~68s por envío (~1.5 Mbit/s). Este script aísla la SUBIDA del cómputo del
modelo: envía payloads de varios tamaños al endpoint y mide el tiempo hasta la respuesta (para
payloads grandes, ese tiempo está dominado por la subida).

Metodología: se corren varios tamaños. Si el tiempo crece ~linealmente con el tamaño, la pendiente
es el ancho de banda real (MB/s) y el intercepto es la latencia fija. Correr una vez DIRECTO y otra
POR PROXY da la comparación que necesita el equipo de red.

Uso (en el servidor de producción):
  python proxy_throughput_bench.py --no-proxy
  python proxy_throughput_bench.py --proxy http://172.17.16.213:1598
  # o dejar que tome el proxy del application.yml si proxyEnabled: true
  python proxy_throughput_bench.py

Requisitos: pip install google-auth pyyaml requests
"""
import argparse
import json
import sys
import time

import yaml
import requests
from google.oauth2 import service_account
from google.auth.transport.requests import Request

# La consola de Windows (cp1252) no encodea caracteres como ≈; forzamos UTF-8 si se puede.
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

DEFAULT_YML = r"E:\NewProyectos\2025\CSjan\workspace1\ms-jurisia-consultaia\src\main\resources\application.yml"
SCOPE = "https://www.googleapis.com/auth/cloud-platform"


def cargar_config(path):
    with open(path, encoding="utf-8") as f:
        cfg = yaml.safe_load(f)
    gcp = cfg["gcp"]
    proxy = cfg.get("sij", {}).get("proxy", {}).get("config", {})
    return {
        "projectId": gcp["projectId"],
        "location": gcp["vectorSearchLocation"],
        "embeddingModel": gcp["embeddingModel"],
        "credInfo": json.loads(gcp["credentials"]["content"]),
        "proxyEnabled": bool(proxy.get("enabled", False)),
        "proxyHost": proxy.get("host"),
        "proxyPort": proxy.get("port"),
    }


def obtener_token(cred_info, proxies):
    creds = service_account.Credentials.from_service_account_info(cred_info, scopes=[SCOPE])
    sess = requests.Session()
    if proxies:
        sess.proxies.update(proxies)
    creds.refresh(Request(session=sess))
    return creds.token


def bench(url, token, proxies, size_mb, timeout):
    # Payload con una cadena grande para alcanzar el tamaño objetivo (el servidor debe leer todo
    # el cuerpo antes de validar/responder, por lo que el tiempo refleja la subida).
    contenido = "A" * int(size_mb * 1024 * 1024)
    body = json.dumps({"instances": [{"content": contenido, "task_type": "RETRIEVAL_QUERY"}]})
    wire_bytes = len(body.encode("utf-8"))
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

    t0 = time.perf_counter()
    try:
        r = requests.post(url, data=body, headers=headers, proxies=proxies, timeout=timeout)
        status = r.status_code
    except requests.exceptions.RequestException as e:
        return {"size_mb": size_mb, "wire_mb": wire_bytes / 1024 / 1024, "elapsed": None,
                "status": f"ERROR: {e.__class__.__name__}", "mbps": None}
    elapsed = time.perf_counter() - t0
    wire_mb = wire_bytes / 1024 / 1024
    mbps = (wire_bytes * 8) / elapsed / 1_000_000  # Mbit/s
    return {"size_mb": size_mb, "wire_mb": wire_mb, "elapsed": elapsed, "status": status, "mbps": mbps}


def main():
    ap = argparse.ArgumentParser(description="Bench de throughput de subida a Vertex AI (proxy vs directo).")
    ap.add_argument("--yml", default=DEFAULT_YML, help="Ruta al application.yml")
    ap.add_argument("--proxy", help="URL de proxy a forzar, ej: http://172.17.16.213:1598")
    ap.add_argument("--no-proxy", action="store_true", help="Forzar conexión directa (ignorar proxy del yaml)")
    ap.add_argument("--sizes", default="1,3,6,9", help="Tamaños en MB, separados por coma")
    ap.add_argument("--timeout", type=int, default=600, help="Timeout por request (s)")
    args = ap.parse_args()

    cfg = cargar_config(args.yml)

    # Resolver proxy: --proxy gana; --no-proxy fuerza directo; si no, se usa el del yaml si está activo.
    if args.no_proxy:
        proxies = None
        modo = "DIRECTO (forzado)"
    elif args.proxy:
        proxies = {"http": args.proxy, "https": args.proxy}
        modo = f"PROXY (forzado) {args.proxy}"
    elif cfg["proxyEnabled"] and cfg["proxyHost"]:
        url_proxy = f"http://{cfg['proxyHost']}:{cfg['proxyPort']}"
        proxies = {"http": url_proxy, "https": url_proxy}
        modo = f"PROXY (yaml) {url_proxy}"
    else:
        proxies = None
        modo = "DIRECTO (yaml: proxy deshabilitado)"

    url = (f"https://{cfg['location']}-aiplatform.googleapis.com/v1/projects/{cfg['projectId']}"
           f"/locations/{cfg['location']}/publishers/google/models/{cfg['embeddingModel']}:predict")

    print(f"Modo:     {modo}")
    print(f"Endpoint: {url}")
    print("Obteniendo token de acceso...", flush=True)
    token = obtener_token(cfg["credInfo"], proxies)

    sizes = [float(s) for s in args.sizes.split(",")]
    print(f"\n{'MB (wire)':>10} | {'tiempo (s)':>10} | {'Mbit/s':>8} | {'MB/s':>6} | status")
    print("-" * 62)
    resultados = []
    for s in sizes:
        res = bench(url, token, proxies, s, args.timeout)
        resultados.append(res)
        if res["elapsed"] is None:
            print(f"{res['wire_mb']:>10.2f} | {'-':>10} | {'-':>8} | {'-':>6} | {res['status']}")
        else:
            print(f"{res['wire_mb']:>10.2f} | {res['elapsed']:>10.2f} | {res['mbps']:>8.2f} | "
                  f"{res['mbps']/8:>6.2f} | {res['status']}")

    # Estimación de ancho de banda por regresión simple (pendiente = MB/s sostenido).
    validos = [r for r in resultados if r["elapsed"] is not None]
    if len(validos) >= 2:
        xs = [r["wire_mb"] for r in validos]
        ys = [r["elapsed"] for r in validos]
        n = len(xs)
        sx, sy = sum(xs), sum(ys)
        sxx = sum(x * x for x in xs)
        sxy = sum(x * y for x, y in zip(xs, ys))
        denom = n * sxx - sx * sx
        if denom != 0:
            pendiente = (n * sxy - sx * sy) / denom   # s por MB
            intercepto = (sy - pendiente * sx) / n     # latencia fija (s)
            if pendiente > 0:
                print(f"\nAncho de banda sostenido ≈ {1/pendiente:.2f} MB/s "
                      f"({8/pendiente:.2f} Mbit/s) | latencia fija ≈ {intercepto:.2f}s")
                print(f"Proyección para 13.3 MB (PDF 10MB en base64): "
                      f"≈ {intercepto + pendiente*13.3:.1f}s por envío")
    print("\nNota: el endpoint responde 200 (truncando) o 4xx según el contenido, pero en ambos "
          "casos solo TRAS recibir todo el cuerpo, por lo que el tiempo mide la subida.")


if __name__ == "__main__":
    sys.exit(main())
