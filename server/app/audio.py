import io
import wave

from .errors import Problem

MAX_AUDIO = 300 * 1024


def validate_wav(data: bytes):
    if len(data) > MAX_AUDIO:
        raise Problem(413, "AUDIO_TOO_LARGE", "音频超过300 KiB")
    try:
        with wave.open(io.BytesIO(data), "rb") as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (
                1,
                2,
                16000,
                "NONE",
            ):
                raise ValueError("format")
            frames = wav.getnframes()
            if not 0 < frames <= 128000 or len(wav.readframes(frames)) != frames * 2:
                raise ValueError("duration or truncated PCM")
    except (wave.Error, EOFError, ValueError) as exc:
        raise Problem(
            415, "INVALID_AUDIO", "需要完整的16kHz单声道16-bit PCM WAV，时长不超过8秒"
        ) from exc
