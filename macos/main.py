import sys
import os
import json
import datetime
import subprocess
import shutil
import platform
from PyQt6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QLineEdit, QPushButton, QComboBox, QFileDialog,
    QProgressBar, QListWidget, QListWidgetItem, QMenu, QMessageBox
)
from PyQt6.QtCore import Qt, QThread, pyqtSignal
from PyQt6.QtGui import QAction, QClipboard, QIcon
import yt_dlp

CONFIG_FILE = os.path.join(os.path.expanduser("~"), ".ytd_config.json")
HISTORY_FILE = os.path.join(os.path.expanduser("~"), ".ytd_history.json")

def get_resource_path(relative_path):
    """ Get absolute path to resource, works for dev and for PyInstaller """
    try:
        # PyInstaller creates a temp folder and stores path in _MEIPASS
        base_path = sys._MEIPASS
    except Exception:
        # On macOS .app bundles, cwd is unpredictable — use the executable's directory
        if getattr(sys, 'frozen', False):
            base_path = os.path.dirname(sys.executable)
        else:
            base_path = os.path.abspath(".")
    return os.path.join(base_path, relative_path)

def get_ffmpeg_name():
    """Return the correct ffmpeg binary name for the current platform."""
    if platform.system() == "Windows":
        return "ffmpeg.exe"
    return "ffmpeg"

def get_ffprobe_name():
    """Return the correct ffprobe binary name for the current platform."""
    if platform.system() == "Windows":
        return "ffprobe.exe"
    return "ffprobe"

class DownloadThread(QThread):
    progress_update = pyqtSignal(dict)
    finished_signal = pyqtSignal(str, str, str) # filename, date, size
    error_signal = pyqtSignal(str)

    def __init__(self, url, format_type, quality, output_folder):
        super().__init__()
        self.url = url
        self.format_type = format_type
        self.quality = quality
        self.output_folder = output_folder
        self.is_cancelled = False

    def run(self):
        try:
            download_url = self.url

            # --- NATIVE SPOTIFY SCRAPER (No API Key Needed) ---
            if "spotify.com" in download_url:
                self.handle_spotify_native(download_url)
                return
            # --------------------------------------------------

            ydl_opts = {
                'outtmpl': os.path.join(self.output_folder, '%(title)s.%(ext)s'),
                'progress_hooks': [self.hook],
                'quiet': True,
                'noprogress': True,
            }

            ffmpeg_path = get_resource_path(get_ffmpeg_name())
            if os.path.exists(ffmpeg_path):
                ydl_opts['ffmpeg_location'] = ffmpeg_path

            if self.format_type == "Video (MP4)":
                ydl_opts['merge_output_format'] = 'mp4'
                if self.quality == "Best":
                    ydl_opts['format'] = 'bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best'
                elif self.quality == "High (1080p)":
                    ydl_opts['format'] = 'bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/best'
                elif self.quality == "Medium (720p)":
                    ydl_opts['format'] = 'bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best'
                elif self.quality == "Low (480p)":
                    ydl_opts['format'] = 'bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/best[height<=480][ext=mp4]/best'

            elif self.format_type == "Audio Only (MP3)":
                ydl_opts['format'] = 'bestaudio/best'
                quality_val = self.quality.replace('kbps', '')
                ydl_opts['postprocessors'] = [{
                    'key': 'FFmpegExtractAudio',
                    'preferredcodec': 'mp3',
                    'preferredquality': quality_val,
                }]

            elif self.format_type == "Audio Only (WAV)":
                ydl_opts['format'] = 'bestaudio/best'
                ydl_opts['postprocessors'] = [{
                    'key': 'FFmpegExtractAudio',
                    'preferredcodec': 'wav',
                }]

            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(download_url, download=True)

                # If it was a search (like our Spotify workaround), we need the first entry
                if 'entries' in info and len(info['entries']) > 0:
                    info = info['entries'][0]

                filename = ydl.prepare_filename(info)

                # If audio extraction happened, extension might change
                if self.format_type == "Audio Only (MP3)":
                    filename = os.path.splitext(filename)[0] + '.mp3'
                elif self.format_type == "Audio Only (WAV)":
                    filename = os.path.splitext(filename)[0] + '.wav'

                if not self.is_cancelled:
                    date_str = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                    size_str = "Unknown"
                    if os.path.exists(filename):
                        size_bytes = os.path.getsize(filename)
                        size_str = self.format_bytes(size_bytes)

                    self.finished_signal.emit(filename, date_str, size_str)

        except Exception as e:
            if str(e) == "Cancelled by user":
                self.error_signal.emit("Download cancelled.")
            else:
                self.error_signal.emit(f"Error: {str(e)}")

    def _extract_spotify_id_and_type(self, url):
        """Extract the resource type and ID from a Spotify URL."""
        import re
        match = re.search(r'open\.spotify\.com/(track|album|playlist)/([a-zA-Z0-9]+)', url)
        if not match:
            raise Exception("Invalid Spotify URL. Please use a link to a track, album, or playlist.")
        return match.group(1), match.group(2)

    # Timeout for all Spotify HTTP requests (connect, read) in seconds
    SPOTIFY_TIMEOUT = (10, 15)

    # Spotify web player's public client ID
    SPOTIFY_CLIENT_ID = 'd8a5ed958d274c2e8ee717e6a4b0971d'

    # Pathfinder GraphQL persisted query hashes (from Spotify web player)
    PATHFINDER_PLAYLIST_HASH = '91d4c2bc3e0cd1bc672281c4f1f59f43ff55ba726ca04a45810d99bd091f3f0e'
    PATHFINDER_ALBUM_HASH = '469874edcad37b7a379d4f22f0083a49ea3d6ae097916120d9bbe3e36ca79e9d'
    PATHFINDER_TRACK_HASH = 'ae85b52abb74d20a4c331d4143d4772c95f34757bfa8c625474b912b9055b5c0'

    def _create_spotify_session(self):
        """Create a requests session with browser-like settings."""
        import requests
        session = requests.Session()
        session.headers.update({
            'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
            'Accept-Language': 'en-US,en;q=0.9',
        })
        return session

    def _get_client_token(self, session):
        """Get a client token from Spotify's client token service."""
        try:
            resp = session.post(
                'https://clienttoken.spotify.com/v1/clienttoken',
                timeout=self.SPOTIFY_TIMEOUT,
                json={
                    'client_data': {
                        'client_version': '1.2.52.442.g0f8a4be4',
                        'client_id': self.SPOTIFY_CLIENT_ID,
                        'js_sdk_data': {
                            'device_brand': '',
                            'device_id': '',
                            'device_model': '',
                            'device_type': '',
                            'os': '',
                            'os_version': '',
                        }
                    }
                },
                headers={
                    'Accept': 'application/json',
                    'Content-Type': 'application/json',
                    'Origin': 'https://open.spotify.com',
                    'Referer': 'https://open.spotify.com/',
                }
            )
            if resp.status_code == 200:
                return resp.json().get('granted_token', {}).get('token')
        except Exception:
            pass
        return None

    def _get_access_token(self, session, client_token=None):
        """Get an anonymous access token from Spotify."""
        import re

        # Visit the main page to collect cookies
        try:
            resp = session.get('https://open.spotify.com/', timeout=self.SPOTIFY_TIMEOUT, headers={
                'Accept': 'text/html,*/*',
            })
            # Try extracting token from page HTML
            if resp.status_code == 200:
                match = re.search(r'"accessToken"\s*:\s*"([^"]+)"', resp.text)
                if match:
                    return match.group(1)
        except Exception:
            pass

        # Try the /api/token endpoint (renamed from /get_access_token in 2026)
        token_headers = {
            'Accept': 'application/json',
            'Referer': 'https://open.spotify.com/',
            'Origin': 'https://open.spotify.com',
            'Sec-Fetch-Dest': 'empty',
            'Sec-Fetch-Mode': 'cors',
            'Sec-Fetch-Site': 'same-origin',
        }
        if client_token:
            token_headers['client-token'] = client_token

        for endpoint in ['https://open.spotify.com/api/token',
                         'https://open.spotify.com/get_access_token']:
            try:
                resp = session.get(
                    endpoint,
                    params={'reason': 'transport', 'productType': 'web-player'},
                    timeout=self.SPOTIFY_TIMEOUT,
                    headers=token_headers,
                )
                if resp.status_code == 200:
                    token = resp.json().get('accessToken')
                    if token:
                        return token
            except Exception:
                pass

        return None

    def _pathfinder_query(self, session, access_token, client_token, operation, sha256, variables):
        """Execute a Pathfinder GraphQL query against Spotify's internal API."""
        import time

        headers = {
            'Authorization': f'Bearer {access_token}',
            'Accept': 'application/json',
            'Content-Type': 'application/json',
            'Origin': 'https://open.spotify.com',
            'Referer': 'https://open.spotify.com/',
            'App-Platform': 'WebPlayer',
            'Sec-Fetch-Dest': 'empty',
            'Sec-Fetch-Mode': 'cors',
            'Sec-Fetch-Site': 'cross-site',
        }
        if client_token:
            headers['client-token'] = client_token

        params = {
            'operationName': operation,
            'variables': json.dumps(variables),
            'extensions': json.dumps({
                'persistedQuery': {'version': 1, 'sha256Hash': sha256}
            }),
        }

        resp = session.get(
            'https://api-partner.spotify.com/pathfinder/v1/query',
            params=params,
            headers=headers,
            timeout=self.SPOTIFY_TIMEOUT,
        )

        if resp.status_code == 429:
            retry_after = min(int(resp.headers.get('Retry-After', '5')), 30)
            time.sleep(retry_after)
            resp = session.get(
                'https://api-partner.spotify.com/pathfinder/v1/query',
                params=params,
                headers=headers,
                timeout=self.SPOTIFY_TIMEOUT,
            )

        resp.raise_for_status()
        return resp.json()

    def _get_tracks_via_pathfinder(self, session, access_token, client_token,
                                    spotify_type, spotify_id):
        """Get ALL tracks using Spotify's internal Pathfinder GraphQL API.
        This bypasses the Feb 2026 API restrictions on playlist items."""
        import time

        tracks = []
        collection_name = "Spotify Download"

        if spotify_type == "playlist":
            uri = f"spotify:playlist:{spotify_id}"
            offset = 0
            limit = 100
            total = None

            while True:
                if self.is_cancelled:
                    raise Exception("Cancelled by user")

                self.progress_update.emit({
                    'percent': 0,
                    'speed': "Fetching track list...",
                    'eta': f"Loaded {offset}/{total or '?'}",
                    'size': collection_name,
                })

                data = self._pathfinder_query(
                    session, access_token, client_token,
                    'fetchPlaylistContents',
                    self.PATHFINDER_PLAYLIST_HASH,
                    {'uri': uri, 'offset': offset, 'limit': limit},
                )

                playlist_v2 = data.get('data', {}).get('playlistV2', {})
                if not playlist_v2:
                    break

                # Get name from first page
                if offset == 0:
                    name = playlist_v2.get('name', '')
                    if name:
                        collection_name = name

                content = playlist_v2.get('content', {})
                if total is None:
                    total = content.get('totalCount', 0)

                items = content.get('items', [])
                if not items:
                    break

                for item in items:
                    try:
                        track_data = item.get('itemV2', {}).get('data', {})
                        if not track_data:
                            continue
                        name = track_data.get('name', '')
                        if not name:
                            continue
                        artist = ''
                        artists_items = track_data.get('artists', {}).get('items', [])
                        if artists_items:
                            artist = artists_items[0].get('profile', {}).get('name', '')
                        query = f"{name} {artist}".strip()
                        if query and query not in tracks:
                            tracks.append(query)
                    except (KeyError, IndexError, TypeError):
                        continue

                offset += limit
                if total and offset >= total:
                    break
                time.sleep(0.3)

        elif spotify_type == "album":
            uri = f"spotify:album:{spotify_id}"

            data = self._pathfinder_query(
                session, access_token, client_token,
                'queryAlbumTracks',
                self.PATHFINDER_ALBUM_HASH,
                {'uri': uri, 'offset': 0, 'limit': 300},
            )

            album_data = data.get('data', {}).get('albumUnion', {})
            if not album_data:
                album_data = data.get('data', {}).get('album', {})

            name = album_data.get('name', '')
            if name:
                collection_name = name

            # Navigate to tracks — structure varies
            tracks_obj = album_data.get('tracksV2', album_data.get('tracks', {}))
            items = tracks_obj.get('items', [])

            for item in items:
                try:
                    track_data = item.get('track', item.get('itemV2', {}).get('data', item))
                    name = track_data.get('name', '')
                    if not name:
                        continue
                    artist = ''
                    artists_items = track_data.get('artists', {}).get('items', [])
                    if artists_items:
                        artist = artists_items[0].get('profile', {}).get('name', '')
                    if not artist:
                        # Try flat artist list
                        flat_artists = track_data.get('artists', [])
                        if isinstance(flat_artists, list) and flat_artists:
                            artist = flat_artists[0].get('name', '')
                    query = f"{name} {artist}".strip()
                    if query and query not in tracks:
                        tracks.append(query)
                except (KeyError, IndexError, TypeError):
                    continue

        elif spotify_type == "track":
            uri = f"spotify:track:{spotify_id}"

            data = self._pathfinder_query(
                session, access_token, client_token,
                'getTrack',
                self.PATHFINDER_TRACK_HASH,
                {'uri': uri},
            )

            track_data = data.get('data', {}).get('trackUnion', {})
            if not track_data:
                track_data = data.get('data', {}).get('track', {})

            name = track_data.get('name', '')
            if name:
                collection_name = name
                artist = ''
                first_artist = track_data.get('firstArtist', {}).get('items', [])
                if first_artist:
                    artist = first_artist[0].get('profile', {}).get('name', '')
                if not artist:
                    artists_items = track_data.get('artists', {}).get('items', [])
                    if artists_items:
                        artist = artists_items[0].get('profile', {}).get('name', '')
                query = f"{name} {artist}".strip()
                if query:
                    tracks.append(query)

        return tracks, collection_name

    def _get_tracks_from_embed(self, session, spotify_type, spotify_id):
        """Scrape track data from Spotify embed page (fallback, no API needed)."""
        import re

        embed_url = f'https://open.spotify.com/embed/{spotify_type}/{spotify_id}'
        resp = session.get(embed_url, timeout=self.SPOTIFY_TIMEOUT, headers={
            'Accept': 'text/html,*/*',
        })
        resp.raise_for_status()
        html = resp.text

        tracks = []
        collection_name = "Spotify Download"
        embed_token = None

        # Search for accessToken in the page
        token_match = re.search(r'"accessToken"\s*:\s*"([^"]+)"', html)
        if token_match:
            embed_token = token_match.group(1)

        # Parse __NEXT_DATA__ JSON
        next_data_match = re.search(
            r'<script\s+id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.DOTALL
        )
        if next_data_match:
            try:
                data = json.loads(next_data_match.group(1))

                # Search for token in JSON
                if not embed_token:
                    embed_token = self._find_in_json(data, ('accessToken', 'access_token'))

                # Extract tracks from entity.trackList
                page_props = data.get('props', {}).get('pageProps', {})
                entity = (page_props.get('state', {}).get('data', {}).get('entity', {})
                          or page_props.get('state', {}).get('data', {})
                          or page_props)

                name = entity.get('name', '')
                if name:
                    collection_name = name

                for t in entity.get('trackList', []):
                    title = t.get('title', '')
                    subtitle = t.get('subtitle', '')
                    if title:
                        query = f"{title} {subtitle}".strip()
                        if query not in tracks:
                            tracks.append(query)
            except (json.JSONDecodeError, KeyError, TypeError):
                pass

        # Fallback: search for trackList JSON in raw HTML
        if not tracks:
            tracklist_match = re.search(r'"trackList"\s*:\s*(\[.*?\])\s*[,}]', html, re.DOTALL)
            if tracklist_match:
                try:
                    for t in json.loads(tracklist_match.group(1)):
                        title = t.get('title', '')
                        subtitle = t.get('subtitle', '')
                        if title:
                            query = f"{title} {subtitle}".strip()
                            if query not in tracks:
                                tracks.append(query)
                except (json.JSONDecodeError, KeyError):
                    pass

        # Get name from meta tags if not found yet
        if collection_name == "Spotify Download":
            og_title = re.search(r'<meta\s+property="og:title"\s+content="([^"]*)"', html)
            if og_title:
                collection_name = og_title.group(1)

        return tracks, collection_name, embed_token

    def _find_in_json(self, obj, keys, depth=0):
        """Recursively search JSON for a key with a string value."""
        if depth > 10:
            return None
        if isinstance(obj, dict):
            for k, v in obj.items():
                if k in keys and isinstance(v, str) and len(v) > 50:
                    return v
                result = self._find_in_json(v, keys, depth + 1)
                if result:
                    return result
        elif isinstance(obj, list):
            for item in obj:
                result = self._find_in_json(item, keys, depth + 1)
                if result:
                    return result
        return None

    def handle_spotify_native(self, url):
        try:
            import time

            spotify_type, spotify_id = self._extract_spotify_id_and_type(url)

            self.progress_update.emit({
                'percent': 0, 'speed': "Connecting to Spotify...",
                'eta': "Authenticating", 'size': ""
            })

            session = self._create_spotify_session()

            # Step 1: Get client token (needed for Pathfinder API)
            client_token = self._get_client_token(session)

            # Step 2: Get access token
            access_token = self._get_access_token(session, client_token)

            tracks_to_download = []
            collection_name = "Spotify Download"
            is_playlist = spotify_type in ("playlist", "album")

            # --- PRIMARY: Pathfinder GraphQL API (gets ALL tracks) ---
            if access_token:
                self.progress_update.emit({
                    'percent': 0, 'speed': "Using Pathfinder API...",
                    'eta': "Fetching all tracks", 'size': ""
                })
                try:
                    tracks_to_download, collection_name = self._get_tracks_via_pathfinder(
                        session, access_token, client_token, spotify_type, spotify_id
                    )
                except Exception:
                    tracks_to_download = []

            # --- FALLBACK 1: Scrape embed page + try its token with Pathfinder ---
            if not tracks_to_download:
                self.progress_update.emit({
                    'percent': 0, 'speed': "Trying embed page...",
                    'eta': "Scraping track data", 'size': ""
                })
                try:
                    embed_tracks, embed_name, embed_token = self._get_tracks_from_embed(
                        session, spotify_type, spotify_id
                    )

                    # If embed gave us a token we didn't have, try Pathfinder with it
                    if embed_token and embed_token != access_token:
                        try:
                            pf_tracks, pf_name = self._get_tracks_via_pathfinder(
                                session, embed_token, client_token, spotify_type, spotify_id
                            )
                            if pf_tracks:
                                tracks_to_download = pf_tracks
                                collection_name = pf_name
                        except Exception:
                            pass

                    # Use embed scraped tracks if Pathfinder still failed
                    if not tracks_to_download and embed_tracks:
                        tracks_to_download = embed_tracks
                        collection_name = embed_name
                except Exception:
                    pass

            # --- FALLBACK 2: oembed for single tracks ---
            if not tracks_to_download and spotify_type == "track":
                try:
                    resp = session.get(
                        'https://open.spotify.com/oembed',
                        params={'url': url},
                        timeout=self.SPOTIFY_TIMEOUT,
                        headers={'Accept': 'application/json'}
                    )
                    if resp.status_code == 200:
                        title = resp.json().get('title', '')
                        if title:
                            tracks_to_download.append(title)
                            collection_name = title
                except Exception:
                    pass

            if not tracks_to_download:
                raise Exception(
                    "Could not find any tracks to download.\n\n"
                    "Possible causes:\n"
                    "- The playlist may be private (make it public)\n"
                    "- Spotify may be blocking requests (try again later)\n"
                    "- The URL may be invalid"
                )

            # Organize into folder for playlists/albums
            target_folder = self.output_folder
            if is_playlist:
                safe_name = "".join(
                    [c for c in collection_name if c.isalnum() or c in (' ', '-', '_')]
                ).strip() or "Spotify Download"
                target_folder = os.path.join(self.output_folder, safe_name)
                os.makedirs(target_folder, exist_ok=True)

            # Download each track via YouTube search
            total = len(tracks_to_download)
            for i, search_query in enumerate(tracks_to_download):
                if self.is_cancelled:
                    raise Exception("Cancelled by user")

                self.progress_update.emit({
                    'percent': (i / total) * 100,
                    'speed': "Searching...",
                    'eta': f"Track {i+1}/{total}",
                    'size': search_query
                })

                ydl_opts = {
                    'outtmpl': os.path.join(target_folder, '%(title)s.%(ext)s'),
                    'format': 'bestaudio/best',
                    'postprocessors': [{
                        'key': 'FFmpegExtractAudio',
                        'preferredcodec': 'mp3',
                        'preferredquality': '320',
                    }],
                    'quiet': True,
                    'no_warnings': True,
                }

                ffmpeg_path = get_resource_path(get_ffmpeg_name())
                if os.path.exists(ffmpeg_path):
                    ydl_opts['ffmpeg_location'] = ffmpeg_path

                with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                    try:
                        info = ydl.extract_info(f"ytsearch1:{search_query}", download=True)
                        if 'entries' in info and len(info['entries']) > 0:
                            entry = info['entries'][0]
                            filename = ydl.prepare_filename(entry).replace(
                                ".webm", ".mp3").replace(".m4a", ".mp3").replace(".opus", ".mp3")
                            date_str = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                            if os.path.exists(filename):
                                size_bytes = os.path.getsize(filename)
                                size_str = self.format_bytes(size_bytes)
                                self.finished_signal.emit(filename, date_str, size_str)
                    except Exception:
                        continue

            self.progress_update.emit({
                'percent': 100,
                'speed': "Done",
                'eta': "Finished",
                'size': f"{total} tracks"
            })

        except Exception as e:
            self.error_signal.emit(f"Spotify Error: {str(e)}")

    def hook(self, d):
        if self.is_cancelled:
            raise Exception("Cancelled by user")

        if d['status'] == 'downloading':
            percent_str = d.get('_percent_str', '0%').strip()
            # Remove ANSI escape codes from speed and eta strings
            speed_str = self.clean_ansi(d.get('_speed_str', 'Unknown speed'))
            eta_str = self.clean_ansi(d.get('_eta_str', 'Unknown ETA'))

            total_bytes = d.get('total_bytes') or d.get('total_bytes_estimate')
            downloaded_bytes = d.get('downloaded_bytes', 0)

            percent = 0.0
            if total_bytes:
                percent = (downloaded_bytes / total_bytes) * 100
            else:
                try:
                    percent = float(self.clean_ansi(percent_str).replace('%', ''))
                except:
                    pass

            size_str = "Unknown size"
            if total_bytes:
                size_str = self.format_bytes(total_bytes)

            self.progress_update.emit({
                'percent': percent,
                'speed': speed_str,
                'eta': eta_str,
                'size': size_str
            })

    def clean_ansi(self, text):
        import re
        ansi_escape = re.compile(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])')
        return ansi_escape.sub('', text).strip()

    def format_bytes(self, bytes_val):
        if bytes_val is None:
            return "Unknown"
        for unit in ['B', 'KB', 'MB', 'GB', 'TB']:
            if bytes_val < 1024.0:
                return f"{bytes_val:.2f} {unit}"
            bytes_val /= 1024.0
        return f"{bytes_val:.2f} PB"

    def cancel(self):
        self.is_cancelled = True

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("YTD")
        self.resize(600, 500)
        self.setStyleSheet(self.get_dark_theme())

        icon_path = get_resource_path("icon.icns")
        if not os.path.exists(icon_path):
            icon_path = get_resource_path("icon.ico")
        if os.path.exists(icon_path):
            self.setWindowIcon(QIcon(icon_path))

        self.config = self.load_config()
        self.history = self.load_history()

        self.init_ui()
        self.check_ffmpeg()

    def get_dark_theme(self):
        return """
        QMainWindow {
            background-color: #1e1e1e;
        }
        QWidget {
            color: #e0e0e0;
            font-family: 'SF Pro Text', 'Helvetica Neue', Arial, sans-serif;
            font-size: 10pt;
        }
        QLineEdit {
            background-color: #2d2d2d;
            border: 1px solid #3d3d3d;
            padding: 8px;
            border-radius: 4px;
        }
        QPushButton {
            background-color: #007acc;
            color: white;
            border: none;
            padding: 8px 16px;
            border-radius: 4px;
        }
        QPushButton:hover {
            background-color: #0098ff;
        }
        QPushButton:disabled {
            background-color: #555555;
            color: #888888;
        }
        QComboBox {
            background-color: #2d2d2d;
            border: 1px solid #3d3d3d;
            padding: 8px;
            border-radius: 4px;
        }
        QComboBox::drop-down {
            border: none;
        }
        QProgressBar {
            border: 1px solid #3d3d3d;
            border-radius: 4px;
            text-align: center;
            background-color: #2d2d2d;
            height: 20px;
        }
        QProgressBar::chunk {
            background-color: #007acc;
            border-radius: 3px;
        }
        QListWidget {
            background-color: #2d2d2d;
            border: 1px solid #3d3d3d;
            border-radius: 4px;
            padding: 5px;
        }
        QListWidget::item {
            padding: 5px;
            border-bottom: 1px solid #3d3d3d;
        }
        QListWidget::item:selected {
            background-color: #007acc;
            border-radius: 3px;
        }
        """

    def init_ui(self):
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        main_layout = QVBoxLayout(central_widget)
        main_layout.setSpacing(15)
        main_layout.setContentsMargins(20, 20, 20, 20)

        # URL Input
        url_layout = QHBoxLayout()

        self.platform_combo = QComboBox()
        self.platform_combo.addItems(["YouTube", "Spotify"])
        self.platform_combo.currentTextChanged.connect(self.on_platform_change)

        self.url_input = QLineEdit()
        self.url_input.setPlaceholderText("Paste YouTube URL here...")
        paste_btn = QPushButton("Paste from Clipboard")
        paste_btn.clicked.connect(self.paste_clipboard)

        url_layout.addWidget(self.platform_combo)
        url_layout.addWidget(self.url_input)
        url_layout.addWidget(paste_btn)
        main_layout.addLayout(url_layout)

        # Format & Quality
        settings_layout = QHBoxLayout()

        self.format_combo = QComboBox()
        self.format_combo.addItems(["Video (MP4)", "Audio Only (MP3)", "Audio Only (WAV)"])
        self.format_combo.currentTextChanged.connect(self.update_quality_options)

        self.quality_combo = QComboBox()
        self.update_quality_options(self.format_combo.currentText())

        settings_layout.addWidget(QLabel("Format:"))
        settings_layout.addWidget(self.format_combo)
        settings_layout.addWidget(QLabel("Quality:"))
        settings_layout.addWidget(self.quality_combo)
        settings_layout.addStretch()
        main_layout.addLayout(settings_layout)

        # Output Folder
        folder_layout = QHBoxLayout()
        self.folder_btn = QPushButton("Choose Folder")
        self.folder_btn.clicked.connect(self.choose_folder)

        # Default to Downloads folder
        default_folder = os.path.join(os.path.expanduser("~"), "Downloads")
        self.folder_label = QLabel(self.config.get("last_folder", default_folder))
        self.folder_label.setStyleSheet("color: #aaaaaa; font-style: italic;")

        folder_layout.addWidget(self.folder_btn)
        folder_layout.addWidget(self.folder_label)
        folder_layout.addStretch()
        main_layout.addLayout(folder_layout)

        # Download Controls
        controls_layout = QHBoxLayout()
        self.download_btn = QPushButton("Download")
        self.download_btn.setMinimumHeight(45)
        self.download_btn.setStyleSheet("font-weight: bold; font-size: 12pt;")
        self.download_btn.clicked.connect(self.start_download)

        self.cancel_btn = QPushButton("Cancel")
        self.cancel_btn.setMinimumHeight(45)
        self.cancel_btn.setStyleSheet("background-color: #cc3300; font-weight: bold; font-size: 12pt;")
        self.cancel_btn.clicked.connect(self.cancel_download)
        self.cancel_btn.hide()

        controls_layout.addWidget(self.download_btn)
        controls_layout.addWidget(self.cancel_btn)
        main_layout.addLayout(controls_layout)

        # Progress
        self.progress_bar = QProgressBar()
        self.progress_bar.setValue(0)
        main_layout.addWidget(self.progress_bar)

        self.status_label = QLabel("Ready")
        self.status_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.status_label.setStyleSheet("color: #aaaaaa;")
        main_layout.addWidget(self.status_label)

        # History
        main_layout.addWidget(QLabel("Download History:"))
        self.history_list = QListWidget()
        self.history_list.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.history_list.customContextMenuRequested.connect(self.show_context_menu)
        main_layout.addWidget(self.history_list)

        self.populate_history()

        # Restore previous settings
        last_platform = self.config.get("last_platform", "YouTube")
        self.platform_combo.setCurrentText(last_platform)

        last_format = self.config.get("last_format", "Video (MP4)")
        self.format_combo.setCurrentText(last_format)
        last_quality = self.config.get("last_quality", "Best")
        if self.quality_combo.findText(last_quality) != -1:
            self.quality_combo.setCurrentText(last_quality)

    def on_platform_change(self, platform):
        if platform == "Spotify":
            self.format_combo.setCurrentText("Audio Only (MP3)")
            self.url_input.setPlaceholderText("Paste Spotify Song/Playlist URL...")
        else:
            self.url_input.setPlaceholderText("Paste YouTube URL here...")

    def paste_clipboard(self):
        clipboard = QApplication.clipboard()
        self.url_input.setText(clipboard.text())

    def update_quality_options(self, format_type):
        self.quality_combo.clear()
        if format_type == "Video (MP4)":
            self.quality_combo.addItems(["Best", "High (1080p)", "Medium (720p)", "Low (480p)"])
            self.quality_combo.show()
        elif format_type == "Audio Only (MP3)":
            self.quality_combo.addItems(["320kbps", "192kbps", "128kbps"])
            self.quality_combo.show()
        elif format_type == "Audio Only (WAV)":
            self.quality_combo.hide()

    def choose_folder(self):
        folder = QFileDialog.getExistingDirectory(self, "Select Download Folder", self.folder_label.text())
        if folder:
            self.folder_label.setText(folder)
            self.config["last_folder"] = folder
            self.save_config()

    def start_download(self):
        url = self.url_input.text().strip()
        if not url:
            QMessageBox.warning(self, "Error", "Please enter a valid YouTube URL.")
            return

        format_type = self.format_combo.currentText()
        quality = self.quality_combo.currentText()
        output_folder = self.folder_label.text()

        # Save preferences
        self.config["last_platform"] = self.platform_combo.currentText()
        self.config["last_format"] = format_type
        self.config["last_quality"] = quality
        self.save_config()

        self.download_btn.hide()
        self.cancel_btn.show()
        self.cancel_btn.setEnabled(True)
        self.progress_bar.setValue(0)
        self.status_label.setText("Starting download...")

        self.thread = DownloadThread(url, format_type, quality, output_folder)
        self.thread.progress_update.connect(self.update_progress)
        self.thread.finished_signal.connect(self.download_finished)
        self.thread.error_signal.connect(self.download_error)
        self.thread.start()

    def cancel_download(self):
        if hasattr(self, 'thread') and self.thread.isRunning():
            self.thread.cancel()
            self.status_label.setText("Cancelling... Please wait.")
            self.cancel_btn.setEnabled(False)

    def update_progress(self, data):
        self.progress_bar.setValue(int(data['percent']))
        self.status_label.setText(f"Speed: {data['speed']} | Size: {data['size']} | ETA: {data['eta']}")

    def download_finished(self, filename, date_str, size_str):
        self.reset_ui()
        self.status_label.setText("Download completed successfully!")
        self.progress_bar.setValue(100)

        history_item = {
            "filename": filename,
            "date": date_str,
            "size": size_str
        }
        self.history.insert(0, history_item)
        self.save_history()
        self.populate_history()
        self.url_input.clear()

    def download_error(self, error_msg):
        self.reset_ui()
        self.status_label.setText("Download failed or cancelled.")
        self.progress_bar.setValue(0)
        QMessageBox.critical(self, "Download Error", error_msg)

    def reset_ui(self):
        self.download_btn.show()
        self.cancel_btn.hide()

    def populate_history(self):
        self.history_list.clear()
        for item in self.history:
            display_text = f"{os.path.basename(item['filename'])}  |  {item['size']}  |  {item['date']}"
            list_item = QListWidgetItem(display_text)
            list_item.setData(Qt.ItemDataRole.UserRole, item['filename'])
            self.history_list.addItem(list_item)

    def show_context_menu(self, position):
        item = self.history_list.itemAt(position)
        if not item:
            return

        filepath = item.data(Qt.ItemDataRole.UserRole)

        menu = QMenu()
        open_file_action = QAction("Open File", self)
        open_folder_action = QAction("Open Containing Folder", self)

        open_file_action.triggered.connect(lambda: self.open_file(filepath))
        open_folder_action.triggered.connect(lambda: self.open_folder(filepath))

        menu.addAction(open_file_action)
        menu.addAction(open_folder_action)
        menu.exec(self.history_list.mapToGlobal(position))

    def open_file(self, filepath):
        if os.path.exists(filepath):
            if platform.system() == "Darwin":
                subprocess.Popen(['open', filepath])
            elif platform.system() == "Windows":
                os.startfile(filepath)
            else:
                subprocess.Popen(['xdg-open', filepath])
        else:
            QMessageBox.warning(self, "Error", "File not found. It may have been moved or deleted.")

    def open_folder(self, filepath):
        if os.path.exists(filepath):
            if platform.system() == "Darwin":
                subprocess.Popen(['open', '-R', filepath])
            elif platform.system() == "Windows":
                subprocess.Popen(['explorer', '/select,', os.path.normpath(filepath)])
            else:
                subprocess.Popen(['xdg-open', os.path.dirname(filepath)])
        else:
            folder = os.path.dirname(filepath)
            if os.path.exists(folder):
                if platform.system() == "Darwin":
                    subprocess.Popen(['open', folder])
                elif platform.system() == "Windows":
                    os.startfile(folder)
                else:
                    subprocess.Popen(['xdg-open', folder])
            else:
                QMessageBox.warning(self, "Error", "Folder not found.")

    def load_config(self):
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, 'r') as f:
                    return json.load(f)
            except:
                pass
        return {}

    def save_config(self):
        with open(CONFIG_FILE, 'w') as f:
            json.dump(self.config, f)

    def load_history(self):
        if os.path.exists(HISTORY_FILE):
            try:
                with open(HISTORY_FILE, 'r') as f:
                    return json.load(f)
            except:
                pass
        return []

    def save_history(self):
        with open(HISTORY_FILE, 'w') as f:
            json.dump(self.history[:50], f) # Keep last 50 items

    def check_ffmpeg(self):
        ffmpeg_path = get_resource_path(get_ffmpeg_name())
        if not os.path.exists(ffmpeg_path) and not shutil.which('ffmpeg'):
            QMessageBox.warning(
                self,
                "FFmpeg Not Found",
                "FFmpeg is not installed or not in your system PATH.\n\n"
                "Audio extraction and video merging may fail.\n"
                "Please install FFmpeg for full functionality.\n\n"
                "On macOS, install with: brew install ffmpeg"
            )

if __name__ == "__main__":
    app = QApplication(sys.argv)
    window = MainWindow()
    window.show()
    sys.exit(app.exec())
