import io
import wave
from pathlib import Path

import av

from .errors import Problem

MAX_AUDIO = 1000 * 1024


def is_opus(data: bytes) -> bool:
    return data.startswith(b"OggS")


def opus_duration(data: bytes) -> float:
    """Require one complete mono Ogg/Opus stream, including CRCs and EOS.

    Granules are always 48kHz, independent of the input recording rate.
    Reject chained/truncated streams rather than letting a decoder salvage them.
    """
    offset, serial, sequence, pre_skip, granule = 0, None, 0, None, 0
    ended = False
    view = memoryview(data)
    while offset < len(data):
        page = view[offset:]
        if len(page) < 27 or page[:5] != b"OggS\x00" or ended:
            raise ValueError("invalid Ogg page")
        count = page[26]
        if len(page) < 27 + count:
            raise ValueError("truncated lacing")
        length = 27 + count + sum(page[27 : 27 + count])
        if length > len(page):
            raise ValueError("truncated Ogg page")
        page = page[:length]
        current_serial = int.from_bytes(page[14:18], "little")
        if serial is None:
            serial = current_serial
            head = page[27 + count :]
            if page[5] != 2 or len(head) != 19 or head[:10] != b"OpusHead\x01\x01":
                raise ValueError("mono OpusHead required")
            if head[18] != 0:
                raise ValueError("unsupported channel mapping")
            pre_skip = int.from_bytes(head[10:12], "little")
        elif page[5] & 2:
            raise ValueError("chained stream")
        if current_serial != serial or int.from_bytes(page[18:22], "little") != sequence:
            raise ValueError("Ogg sequence mismatch")
        expected_crc = int.from_bytes(page[22:26], "little")
        crc = 0
        for index, value in enumerate(page):
            byte = 0 if 22 <= index < 26 else value
            crc = ((crc << 8) & 0xFFFFFFFF) ^ OGG_CRC[((crc >> 24) ^ byte) & 255]
        if crc != expected_crc:
            raise ValueError("Ogg checksum mismatch")
        granule = int.from_bytes(page[6:14], "little")
        ended = bool(page[5] & 4)
        offset += length
        sequence += 1
    if not ended or pre_skip is None or not 0 < granule - pre_skip <= 30 * 48000:
        raise ValueError("missing EOS or invalid duration")
    return (granule - pre_skip) / 48000


def _crc_table():
    table = []
    for value in range(256):
        crc = value << 24
        for _ in range(8):
            crc = ((crc << 1) ^ (0x04C11DB7 if crc & 0x80000000 else 0)) & 0xFFFFFFFF
        table.append(crc)
    return table


OGG_CRC = _crc_table()


def decode_opus(data: bytes) -> bytes:
    """Decode into a bounded PCM representation for validation and inspection."""
    declared_duration = opus_duration(data)
    with av.open(io.BytesIO(data), format="ogg") as container:
        if len(container.streams) != 1 or container.streams[0].codec_context.name != "opus":
            raise ValueError("one Opus stream required")
        resampler = av.AudioResampler(format="s16", layout="mono", rate=16000)
        pcm = bytearray()
        samples = 0
        for frame in container.decode(audio=0):
            if len(frame.layout.channels) != 1:
                raise ValueError("mono required")
            samples += frame.samples
            if samples > frame.sample_rate * 30:
                raise ValueError("decoded audio too long")
            for converted in resampler.resample(frame):
                pcm.extend(bytes(converted.planes[0])[: converted.samples * 2])
                if len(pcm) > 960000:
                    raise ValueError("decoded audio too long")
        for converted in resampler.resample(None):
            pcm.extend(bytes(converted.planes[0])[: converted.samples * 2])
        if abs(samples - round(declared_duration * 48000)) > 1:
            raise ValueError("Ogg duration does not match decoded samples")
        if not pcm or len(pcm) > 960000:
            raise ValueError("empty or excessive decoded audio")
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(16000)
        wav.writeframes(pcm)
    return output.getvalue()


def validate_audio(data: bytes):
    if not is_opus(data):
        return validate_wav(data)
    if len(data) > MAX_AUDIO:
        raise Problem(413, "AUDIO_TOO_LARGE", "音频超过1000 KiB")
    try:
        decode_opus(data)
    except (ValueError, av.FFmpegError, EOFError) as exc:
        raise Problem(
            415, "INVALID_AUDIO", "需要完整的单声道Ogg Opus或16kHz PCM WAV，最长30秒"
        ) from exc


def duration(path: Path) -> float:
    if path.suffix == ".opus":
        return opus_duration(path.read_bytes())
    with wave.open(str(path), "rb") as wav:
        return wav.getnframes() / wav.getframerate()


def validate_wav(data: bytes):
    if len(data) > MAX_AUDIO:
        raise Problem(413, "AUDIO_TOO_LARGE", "音频超过1000 KiB")
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
            if not 0 < frames <= 480000 or len(wav.readframes(frames)) != frames * 2:
                raise ValueError("duration or truncated PCM")
    except (wave.Error, EOFError, ValueError) as exc:
        raise Problem(
            415, "INVALID_AUDIO", "需要完整的16kHz单声道16-bit PCM WAV，时长不超过30秒，请分句说"
        ) from exc
