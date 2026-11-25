import yt_dlp
import json
import sys


def download(quickjs_bin: str, video_id: str) -> str:
    """Resolve a YouTube video ID to a playable stream URL using yt-dlp.
    
    yt-dlp handles ALL the hard stuff:
    - Signature deobfuscation (cipher)
    - N-parameter transformation (throttle avoidance)
    - PO token generation
    - Format selection
    - Client rotation
    """
    opts = {
        # Strictly grab audio-only streams (m4a or webm) instead of 16MB MP4 videos
        "format": "bestaudio[acodec!=none][ext=m4a]/bestaudio[acodec!=none][ext=webm]/bestaudio[acodec!=none]/best[acodec!=none]",
        "skip_download": True,
        "quiet": True,
        "no_warnings": True,
        "geo_bypass": True,
        "no_check_certificates": True,
        "extractor_args": {
            "youtube": {
                # ios and tv clients return direct audio links much faster than web
                "player_client": ["ios", "tv", "android", "web"],
                "player_skip": ["webpage", "configs"],
            }
        },
        "source_address": "0.0.0.0",
        "socket_timeout": 15,
    }


    if quickjs_bin:
        opts["js_runtimes"] = {"quickjs": {"path": quickjs_bin}}

    try:
        ydl = yt_dlp.YoutubeDL(opts)
        info = ydl.extract_info(video_id, download=False)
        
        if info is None:
            return json.dumps({"error": "extract_info returned None"})
        
        # Build a clean response with all the info we need
        result = {
            "id": info.get("id", video_id),
            "title": info.get("title", ""),
            "url": info.get("url"),
            "ext": info.get("ext", "mp4"),
            "filesize": info.get("filesize") or info.get("filesize_approx") or 0,
            "format_id": info.get("format_id", ""),
            "acodec": info.get("acodec", ""),
            "abr": info.get("abr", 0),
            "tbr": info.get("tbr", 0),
            "duration": info.get("duration", 0),
            # Include all available formats for fallback
            "formats": [],
        }
        
        # Process each format
        for fmt in info.get("formats", []):
            fmt_entry = {
                "format_id": fmt.get("format_id", ""),
                "url": fmt.get("url"),
                "ext": fmt.get("ext", ""),
                "acodec": fmt.get("acodec", ""),
                "vcodec": fmt.get("vcodec", "none"),
                "abr": fmt.get("abr", 0),
                "tbr": fmt.get("tbr", 0),
                "filesize": fmt.get("filesize") or 0,
                "format_note": fmt.get("format_note", ""),
                "protocol": fmt.get("protocol", ""),
                "http_headers": fmt.get("http_headers", {}),
            }
            result["formats"].append(fmt_entry)
        
        # If top-level URL is missing, sort and pick the best audio-only stream
        if not result["url"]:
            audio_only = [
                f for f in result["formats"]
                if f["url"]
                and f["vcodec"] == "none"
                and f["acodec"] not in ("none", "", None)
                and not str(f["format_id"]).startswith("sb")
            ]
            audio_only.sort(
                key=lambda x: (x["ext"] in ("m4a", "webm"), x["abr"] or 0),
                reverse=True
            )
            if audio_only:
                best = audio_only
                result["url"] = best["url"]
                result["format_id"] = best["format_id"]
                result["ext"] = best["ext"]
                result["filesize"] = best["filesize"]
                result["acodec"] = best["acodec"]
                result["abr"] = best["abr"]
            # If still no URL, try any format with a URL
            elif result["formats"]:
                for fmt in result["formats"]:
                    if fmt["url"]:
                        result["url"] = fmt["url"]
                        result["format_id"] = fmt["format_id"]
                        result["ext"] = fmt["ext"]
                        result["filesize"] = fmt["filesize"]
                        break

        return json.dumps(result, indent=4)
        
    except yt_dlp.utils.DownloadError as e:
        return json.dumps({"error": str(e), "id": video_id})
    except Exception as e:
        return json.dumps({"error": f"{type(e).__name__}: {e}", "id": video_id})


def upgrade(package_name):
    try:
        import ensurepip
        ensurepip.bootstrap()
    except Exception as e:
        print(f"Error running ensurepip: {e}")
    try:
        import pip
        from pip._internal import main as pip_main
        pip_main(["install", "--upgrade", package_name])
        print(f"Successfully upgraded {package_name}")
    except Exception as e:
        print(f"Error upgrading package {package_name}: {e}")


def list_playlist(quickjs_bin: str, url: str) -> str:
    """List tracks in a YouTube Music / YouTube playlist.
    Returns JSON with title/artist for each track."""
    opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": True,
        "skip_download": True,
        "ignoreerrors": True,
    }
    if quickjs_bin:
        opts["jsintfs"] = quickjs_bin

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
            if info is None:
                return json.dumps({"entries": []})
            entries = info.get("entries") or []
            result = []
            for e in entries:
                if e is None:
                    continue
                result.append({
                    "title": e.get("title") or "Unknown",
                    "artist": e.get("uploader") or e.get("channel") or "",
                })
            return json.dumps({"entries": result})
    except Exception as e:
        return json.dumps({"error": str(e)})
