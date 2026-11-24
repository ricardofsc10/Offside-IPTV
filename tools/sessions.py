"""Resume os ficheiros de sessão do Offside: um por canal visto mais de um minuto e um por
cada canal que teve algum problema, mesmo nos primeiros segundos.

Copiar do Shield e resumir:

    python tools/sessions.py --pull IP_DO_SHIELD:5555

Só resumir uma pasta já copiada:

    python tools/sessions.py caminho/para/sessions

Com --events, mostra também a linha do tempo de cada sessão com problemas.

Os ficheiros só têm números e um identificador curto do canal, sem nomes nem a conta.
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile

REMOTE = "/sdcard/Android/data/io.github.ricardofsc10.offsideplayer/files/sessions"

def minutes(seconds):
    return f"{seconds / 60:.0f}m" if seconds >= 60 else f"{seconds}s"


COLUMNS = [
    ("início", lambda s: s["startedAt"][5:16].replace("T", " ")),
    ("canal", lambda s: s["channel"]),
    ("resultado", lambda s: {"problem": "PROBLEMA", "watched": "ok"}.get(s.get("outcome"), "ok")),
    ("aberto", lambda s: minutes(s.get("openS", s["watchedS"]))),
    ("visto", lambda s: minutes(s["watchedS"])),
    ("vídeo", lambda s: f"{s.get('height') or '?'}p{round(s['fps']) if s.get('fps') else ''}"),
    ("kbps", lambda s: s.get("kbpsAvg") or "-"),
    ("arranque", lambda s: f"{s['firstFrameMs'] / 1000:.1f}s" if s.get("firstFrameMs") else "-"),
    ("paragens", lambda s: s["rebuffers"]),
    ("parado", lambda s: f"{s['stalledMs'] / 1000:.0f}s"),
    ("religou", lambda s: s["reconnects"]),
    ("tentou", lambda s: s.get("retries", 0)),
    ("falhas", lambda s: s["deliveryGaps"]),
    ("maior", lambda s: f"{s['longestReadMs'] / 1000:.1f}s"),
    ("buffer", lambda s: f"{(s.get('bufferAvgMs') or 0) / 1000:.1f}s/{(s.get('bufferMinMs') or 0) / 1000:.1f}s"),
    ("lento", lambda s: f"{s['slowdowns']}x {s['slowedMs'] / 1000:.0f}s"),
    ("perdidas", lambda s: s["droppedFrames"]),
]

LEGEND = """
resultado: PROBLEMA quando houve paragem, nova ligação, nova tentativa ou falha. aberto: tempo
desde que o canal abriu. visto: tempo com imagem. tentou: novas tentativas de abrir. kbps: débito médio. arranque: até à primeira imagem.
paragens: vezes que a imagem parou. parado: tempo total parado. religou: novas ligações.
falhas: leituras que esperaram 1 s ou mais pelo servidor. maior: a espera mais longa.
buffer: média/mínimo com imagem. lento: vezes e tempo a 0,96x. perdidas: imagens descartadas.
"""


def pull(device: str) -> str:
    target = tempfile.mkdtemp(prefix="offside-sessions-")
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    result = subprocess.run(["adb", "-s", device, "pull", REMOTE, target], env=env, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"adb pull falhou: {result.stderr.strip() or result.stdout.strip()}")
    return os.path.join(target, "sessions")


def load(folder: str) -> list:
    sessions = []
    for name in sorted(os.listdir(folder)):
        if name.endswith(".json"):
            with open(os.path.join(folder, name), encoding="utf-8") as handle:
                sessions.append(json.load(handle))
    return sessions


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder", nargs="?", help="pasta com os ficheiros .json")
    parser.add_argument("--pull", metavar="DEVICE", help="copia primeiro do aparelho por ADB")
    parser.add_argument("--last", type=int, default=30, help="quantas sessões mostrar (30)")
    parser.add_argument("--events", action="store_true", help="mostra a linha do tempo das sessões com problemas")
    args = parser.parse_args()
    if not args.folder and not args.pull:
        parser.error("indica a pasta ou --pull")
    folder = pull(args.pull) if args.pull else args.folder
    sessions = load(folder)[-args.last:]
    if not sessions:
        print("Sem sessões.")
        return
    rows = [[str(get(s)) for _, get in COLUMNS] for s in sessions]
    widths = [max(len(title), *(len(row[i]) for row in rows)) for i, (title, _) in enumerate(COLUMNS)]
    print("  ".join(title.ljust(widths[i]) for i, (title, _) in enumerate(COLUMNS)))
    for row in rows:
        print("  ".join(value.ljust(widths[i]) for i, value in enumerate(row)))
    print(LEGEND.rstrip())
    if args.events:
        for session in sessions:
            if session.get("outcome") != "problem":
                continue
            started = session["startedAt"][5:16].replace("T", " ")
            failure = f"  falha: {session['failure']}" if session.get("failure") else ""
            print(f"\n{started}  {session['channel']}{failure}")
            for event in session.get("events", []):
                detail = f"  {event['detail']}" if event.get("detail") else ""
                print(f"  {event['t'] / 1000:7.1f}s  {event['type']}{detail}")
    if args.pull:
        print(f"\nCopiado para {folder}")


if __name__ == "__main__":
    main()
