#!/usr/bin/env python3
"""Servidor Xtream falso, só com dados sintéticos, para testar a app sem uma conta real.

Utilização:
    python tools/fake_xtream.py [--channels 20000] [--movies 50000] [--series 2000] [--port 8099]
                                [--posters] [--movie-file FILME.mp4] [--episode-file EPISODIO.mkv]
    adb reverse tcp:8099 tcp:8099

Na app: servidor 127.0.0.1:8099, utilizador test, password test.
Qualquer outra password devolve auth=0. Os canais respondem sempre 404.

--posters gera uma capa de cor lisa para cada filme e série.
--live-file serve esse ficheiro MPEG-TS em todos os canais, em ciclo e ao ritmo do vídeo, como um
canal em direto. --live-seconds é a duração do ficheiro, para acertar o ritmo. Os canais com ID
múltiplo de 5 param de enviar durante --live-gap-ms ao fim de --live-gap-at segundos, para testar
as falhas de entrega no arranque.
--showcase dá nomes inventados com ar de real a canais, categorias, filmes e séries, liga os canais
a um guia e serve esse guia em /epg.xml, com programas à volta da hora atual. Serve para ver o
design com conteúdo parecido com o real. Nenhum nome é de um canal, clube ou filme real.
--movie-file e --episode-file servem esses ficheiros para todos os filmes e episódios, com
suporte de Range para o player poder saltar. Sem eles, filmes e episódios respondem 404.
"""
import argparse
import json
import os
import struct
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

CATEGORIES = 40
VOD_CATEGORIES = 25
SEASONS = 3
EPISODES_PER_SEASON = 8


def account(authenticated):
    if not authenticated:
        return {"user_info": {"auth": 0}}
    return {
        "user_info": {
            "username": "test",
            "auth": 1,
            "status": "Active",
            "exp_date": str(int(time.time()) + 30 * 86400),
            "active_cons": "0",
            "max_connections": "1",
            "allowed_output_formats": ["m3u8", "ts"],
        },
        "server_info": {"url": "127.0.0.1", "port": "8099"},
    }


# Nomes inventados para --showcase. A ordem das marcas segue a das categorias.
SHOW_CATEGORIES = [
    "PT | Desporto", "PT | Generalistas", "PT | Notícias", "PT | Infantil", "PT | Filmes e Séries",
    "PT | Documentários", "ES | Deportes", "UK | Sports", "FR | Sport", "Eventos ao vivo",
]
SHOW_BRANDS = [
    ["Arena", "Golo TV", "Estádio", "Bancada", "Relvado"],
    ["Canal Sete", "Atlântico", "Lusa Um", "Tejo TV"],
    ["Notícias 24", "Agora Info", "Linha Direta"],
    ["Kids Mundo", "Pirilampo", "Desenhos Já"],
    ["Cine Norte", "Sala Escura", "Séries Mais"],
    ["Planeta Vivo", "Rota", "Histórias"],
    ["Graderío", "Tribuna Sur"],
    ["Pitchside", "Kickoff One"],
    ["Tribune", "Les Tribunes"],
    ["Evento"],
]
SPORT_CATEGORIES = (0, 6, 7, 8, 9)
SHOW_SUFFIXES = ["HD", "FHD", "4K", ""]
CLUBS = [
    "Leões do Mar", "Atlético Norte", "Ribeira FC", "União Serrana", "Desportivo Foz", "Académica Sul",
    "Real Planalto", "Sporting Vale", "Marítimo Azul", "Clube da Ponte", "Estrela Velha", "Os Unidos",
]
COMPETITIONS = ["Liga", "Taça", "Liga dos Campeões", "Liga Europa", "Supertaça"]
SHOWS = {
    1: ["Telejornal", "Manhã Viva", "O Concurso", "Novela: Laços de Família", "Grande Entrevista", "Cinema da Noite"],
    2: ["Jornal das 20", "Última Hora", "Economia Hoje", "Mundo em Minutos", "Debate"],
    3: ["Os Pequenos Exploradores", "Aventura no Jardim", "Robôs Amigos", "Contos de Encantar"],
    4: ["O Último Inverno", "Cidade Partida", "A Casa do Lago", "Noite Sem Fim", "Segredos do Porto"],
    5: ["Oceanos", "Rotas da Seda", "Vida Selvagem", "Engenharia Impossível"],
}
GENRES = {1: "Generalista", 2: "Notícias", 3: "Infantil", 4: "Filme", 5: "Documentário"}
MOVIE_WORDS = (
    ["O Último", "A Noite do", "Os Filhos do", "A Sombra do", "O Regresso do", "Para Lá do", "O Silêncio do", "A Casa do"],
    ["Inverno", "Rio", "Farol", "Deserto", "Vento", "Comboio", "Verão", "Lobo", "Mar", "Norte"],
)
SERIES_WORDS = (
    ["Crónicas de", "Os Segredos de", "Noites em", "A Família de", "Fronteira de", "Detetives de"],
    ["Lisboa", "Évora", "Sintra", "Aveiro", "Faro", "Braga", "Tavira", "Viseu"],
)


def show_category(i):
    return (i - 1) % len(SHOW_CATEGORIES)


def show_channel_name(i):
    category = show_category(i)
    brands = SHOW_BRANDS[category]
    turn = (i - 1) // len(SHOW_CATEGORIES)
    brand = brands[turn % len(brands)]
    number = turn // len(brands) + 1
    label = SHOW_CATEGORIES[category]
    prefix = label.split(" | ")[0] if " | " in label else "PT"
    suffix = SHOW_SUFFIXES[i % len(SHOW_SUFFIXES)]
    parts = [f"{prefix}:", brand, str(number) if category in SPORT_CATEGORIES else "", suffix]
    return " ".join(part for part in parts if part)


def show_title(words, i):
    first, second = words
    title = f"{first[i % len(first)]} {second[(i * 3 + i // len(first)) % len(second)]}"
    return title + (f" {i // 80 + 1}" if i >= 80 else "")


def xml_escape(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def xmltv_time(seconds):
    return time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(seconds))


def epg_xml(count):
    """Guia de 3 horas antes a 12 horas depois da hora atual. Alguns canais ficam de fora."""
    now = int(time.time()) // 1800 * 1800
    parts = ['<?xml version="1.0" encoding="UTF-8"?>', "<tv>"]
    listed = [i for i in range(1, count + 1) if i % 7]
    for i in listed:
        parts.append(f'<channel id="show{i}.pt"><display-name>{xml_escape(show_channel_name(i))}</display-name></channel>')
    for i in listed:
        category = show_category(i)
        start = now - 3 * 3600 - (i % 4) * 900
        k = 0
        while start < now + 12 * 3600:
            seed = i * 31 + k * 7
            if category in SPORT_CATEGORIES:
                home = CLUBS[seed % len(CLUBS)]
                away = CLUBS[(seed * 5 + 3) % len(CLUBS)]
                if home == away:
                    away = CLUBS[(seed + 1) % len(CLUBS)]
                if k % 3 == 2:
                    title, desc, length = "Resumo da Jornada", "Os golos e os casos da jornada.", 3600
                else:
                    competition = COMPETITIONS[seed % len(COMPETITIONS)]
                    title = f"{competition}: {home} - {away}"
                    desc = f"Em direto, {home} recebe o {away} num jogo da {competition}. Antevisão, jogo e análise."
                    length = 6300
                kind = "Desporto"
            else:
                titles = SHOWS.get(category, SHOWS[1])
                title = titles[seed % len(titles)]
                desc = f"{title}. Episódio {seed % 40 + 1}."
                length = [1800, 2700, 3600, 5400][seed % 4]
                kind = GENRES.get(category, "Generalista")
            parts.append(
                f'<programme start="{xmltv_time(start)}" stop="{xmltv_time(start + length)}" channel="show{i}.pt">'
                f'<title lang="pt">{xml_escape(title)}</title><desc lang="pt">{xml_escape(desc)}</desc>'
                f'<category lang="pt">{kind}</category></programme>'
            )
            start += length
            k += 1
    parts.append("</tv>")
    return "\n".join(parts).encode("utf-8")


def categories():
    if Handler.showcase:
        return [
            {"category_id": str(i + 1), "category_name": name, "parent_id": 0}
            for i, name in enumerate(SHOW_CATEGORIES)
        ]
    return [
        {"category_id": str(i), "category_name": f"Categoria {i}", "parent_id": 0}
        for i in range(1, CATEGORIES + 1)
    ]


def channel(i):
    if Handler.showcase:
        category = show_category(i)
        return {
            "num": i,
            "name": show_channel_name(i),
            "stream_type": "live",
            "stream_id": i,
            "stream_icon": "",
            # Uns ligam-se ao guia pelo ID, outros só pelo nome, e os múltiplos de 7 ficam sem guia.
            "epg_channel_id": f"show{i}.pt" if i % 3 else None,
            "category_id": str(category + 1),
            "tv_archive": 1 if category == 0 else 0,
            "tv_archive_duration": 3 if category == 0 else 0,
        }
    # Alterna números e texto, como fazem os servidores reais.
    return {
        "num": i if i % 2 else str(i),
        "name": f"Canal Fictício {i}",
        "stream_type": "live",
        "stream_id": i if i % 2 else str(i),
        "stream_icon": "",
        "epg_channel_id": None,
        "category_id": str(i % CATEGORIES + 1),
    }


def vod_categories(prefix):
    return [
        {"category_id": str(i), "category_name": f"{prefix} {i}", "parent_id": 0}
        for i in range(1, VOD_CATEGORIES + 1)
    ]


def png(width, height, rgb):
    """PNG de cor lisa, só com a biblioteca padrão."""
    row = b"\x00" + bytes(rgb) * width
    raw = row * height

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    signature = b"\x89PNG\r\n\x1a\n"
    return signature + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def poster_color(i):
    return ((i * 67) % 200 + 30, (i * 131) % 200 + 30, (i * 199) % 200 + 30)


def artwork_url(kind, i):
    return f"http://127.0.0.1:{Handler.port}/artwork/{kind}/{i}.png" if Handler.posters else ""


def movie(i):
    return {
        "num": i,
        "name": show_title(MOVIE_WORDS, i) if Handler.showcase else f"Filme Fictício {i}",
        "stream_type": "movie",
        "stream_id": i if i % 2 else str(i),
        "stream_icon": artwork_url("movie", i),
        "rating": str(i % 10),
        "added": str(1700000000 + i),
        "category_id": str(i % VOD_CATEGORIES + 1),
        "container_extension": "mkv" if i % 3 == 0 else "mp4",
    }


def series(i):
    return {
        "num": i,
        "name": show_title(SERIES_WORDS, i) if Handler.showcase else f"Série Fictícia {i}",
        "series_id": i,
        "cover": artwork_url("series", i),
        "plot": f"Resumo fictício da série {i}.",
        "releaseDate": "2020-01-01",
        "rating": "7",
        "last_modified": str(1700000000 + i),
        "category_id": str(i % VOD_CATEGORIES + 1),
    }


def series_info(series_id):
    # As temporadas vêm como objeto, a forma mais comum nos servidores reais.
    episodes = {}
    for season in range(1, SEASONS + 1):
        episodes[str(season)] = [
            {
                "id": str(series_id * 1000 + season * 100 + number),
                "episode_num": number,
                "title": f"Episódio {number}",
                "container_extension": "mkv",
                "season": season,
                "info": {"duration_secs": 2700, "plot": "Resumo fictício do episódio."},
            }
            for number in range(1, EPISODES_PER_SEASON + 1)
        ]
    return {"seasons": [], "info": {"name": f"Série Fictícia {series_id}"}, "episodes": episodes}


def movie_info(_vod_id):
    return {
        "info": {
            "plot": "Resumo fictício do filme.",
            "duration_secs": 5400,
            "releasedate": "2021-06-01",
            "genre": "Drama",
        },
        "movie_data": {},
    }


class Handler(BaseHTTPRequestHandler):
    channels = 0
    movies = 0
    series = 0
    port = 8099
    posters = False
    showcase = False
    live_file = None
    live_data = b""
    live_seconds = 100.0
    live_gap_at = 15.0
    live_gap_ms = 3000
    movie_file = None
    episode_file = None

    def serve_file(self, path):
        size = os.path.getsize(path)
        start, end = 0, size - 1
        requested = self.headers.get("Range", "")
        if requested.startswith("bytes="):
            first, _, last = requested[len("bytes="):].partition("-")
            start = int(first) if first else 0
            end = min(int(last), size - 1) if last else size - 1
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        kind = "video/mp4" if path.endswith(".mp4") else "video/x-matroska"
        self.send_header("Content-Type", kind)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        try:
            with open(path, "rb") as source:
                source.seek(start)
                remaining = end - start + 1
                while remaining > 0:
                    block = source.read(min(65536, remaining))
                    if not block:
                        break
                    self.wfile.write(block)
                    remaining -= len(block)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass  # O player fecha ligações ao saltar.

    def serve_live(self, path):
        parts = path.strip("/").split("/")
        if len(parts) != 4 or parts[1:3] != ["test", "test"] or not parts[3].endswith(".ts") or self.live_file is None:
            self.send_error(404)
            return
        try:
            stream_id = int(parts[3][:-3])
        except ValueError:
            self.send_error(404)
            return
        data = self.live_data
        rate = len(data) / self.live_seconds
        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.end_headers()
        chunk = 32 * 1024
        position = 0
        started = time.monotonic()
        sent = 0
        gap_done = stream_id % 5 != 0
        try:
            while True:
                if not gap_done and time.monotonic() - started >= self.live_gap_at:
                    gap_done = True
                    time.sleep(self.live_gap_ms / 1000)
                    # Depois da falha o servidor não recupera o atraso: continua ao ritmo do vídeo.
                    started += self.live_gap_ms / 1000
                block = data[position:position + chunk]
                if not block:
                    position = 0
                    continue
                self.wfile.write(block)
                position += len(block)
                sent += len(block)
                wait = sent / rate - (time.monotonic() - started)
                if wait > 0:
                    time.sleep(wait)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def serve_media(self, path):
        parts = path.strip("/").split("/")
        if len(parts) != 4 or parts[1:3] != ["test", "test"]:
            self.send_error(404)
            return
        source = self.movie_file if parts[0] == "movie" else self.episode_file
        if source is None:
            self.send_error(404)
            return
        self.serve_file(source)

    def serve_artwork(self, path):
        parts = path.strip("/").split("/")
        try:
            number = int(parts[2].split(".")[0])
        except (IndexError, ValueError):
            self.send_error(404)
            return
        body = png(200, 300, poster_color(number))
        self.send_response(200)
        self.send_header("Content-Type", "image/png")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def write_array(self, count, make):
        self.wfile.write(b"[")
        for i in range(1, count + 1):
            prefix = b"," if i > 1 else b""
            self.wfile.write(prefix + json.dumps(make(i)).encode())
        self.wfile.write(b"]")

    def log_message(self, *args):
        pass

    def do_GET(self):
        url = urlparse(self.path)
        if url.path.startswith("/live/"):
            self.serve_live(url.path)
            return
        if url.path.startswith("/movie/") or url.path.startswith("/series/"):
            self.serve_media(url.path)
            return
        if url.path.startswith("/artwork/"):
            self.serve_artwork(url.path)
            return
        if url.path == "/epg.xml" and self.showcase:
            body = epg_xml(self.channels)
            self.send_response(200)
            self.send_header("Content-Type", "application/xml")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if url.path != "/player_api.php":
            self.send_error(404)
            return
        query = parse_qs(url.query)
        authenticated = query.get("username") == ["test"] and query.get("password") == ["test"]
        action = query.get("action", [None])[0]

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        if not authenticated or action is None:
            self.wfile.write(json.dumps(account(authenticated)).encode())
        elif action == "get_live_categories":
            self.wfile.write(json.dumps(categories()).encode())
        elif action == "get_live_streams":
            self.write_array(self.channels, channel)
        elif action == "get_vod_categories":
            self.wfile.write(json.dumps(vod_categories("Filmes")).encode())
        elif action == "get_series_categories":
            self.wfile.write(json.dumps(vod_categories("Séries")).encode())
        elif action == "get_vod_streams":
            self.write_array(self.movies, movie)
        elif action == "get_series":
            self.write_array(self.series, series)
        elif action == "get_series_info":
            series_id = int(query.get("series_id", ["0"])[0])
            self.wfile.write(json.dumps(series_info(series_id)).encode())
        elif action == "get_vod_info":
            self.wfile.write(json.dumps(movie_info(query.get("vod_id", [None])[0])).encode())
        else:
            self.wfile.write(b"[]")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--channels", type=int, default=20000)
    parser.add_argument("--movies", type=int, default=50000)
    parser.add_argument("--series", type=int, default=2000)
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--posters", action="store_true")
    parser.add_argument("--movie-file")
    parser.add_argument("--episode-file")
    parser.add_argument("--showcase", action="store_true")
    parser.add_argument("--live-file")
    parser.add_argument("--live-seconds", type=float, default=100.0)
    parser.add_argument("--live-gap-at", type=float, default=15.0)
    parser.add_argument("--live-gap-ms", type=int, default=3000)
    args = parser.parse_args()
    Handler.showcase = args.showcase
    Handler.live_file = args.live_file
    if args.live_file:
        with open(args.live_file, "rb") as source:
            Handler.live_data = source.read()
    Handler.live_seconds = args.live_seconds
    Handler.live_gap_at = args.live_gap_at
    Handler.live_gap_ms = args.live_gap_ms
    Handler.channels = args.channels
    Handler.movies = args.movies
    Handler.series = args.series
    Handler.port = args.port
    Handler.posters = args.posters
    Handler.movie_file = args.movie_file
    Handler.episode_file = args.episode_file
    print(
        f"A servir {args.channels} canais, {args.movies} filmes e {args.series} séries em 127.0.0.1:{args.port}",
        flush=True,
    )
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
