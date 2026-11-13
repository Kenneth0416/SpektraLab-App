"""Portable model locations and conservative dtype/device selection.

Models are local/cache-only by default. Downloads require HF_HUB_OFFLINE=0
explicitly; never enable that for the repository's smoke tests.
"""
import os
os.environ.setdefault('HF_HUB_OFFLINE', '1')

MODELS = {
    'VOICE_DESIGN': 'Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign',
    'TTS_BASE': 'Qwen/Qwen3-TTS-12Hz-1.7B-Base',
    'ALIGNER': 'Qwen/Qwen3-ForcedAligner-0.6B',
    'ASR': 'Qwen/Qwen3-ASR-1.7B',
}


def model_path(kind):
    return os.environ.get('PROMO_' + kind + '_MODEL', MODELS[kind])


def model_options(torch):
    device = os.environ.get('PROMO_DEVICE', 'auto')
    if device == 'auto':
        device = 'cuda' if torch.cuda.is_available() else ('mps' if torch.backends.mps.is_available() else 'cpu')
    dtype = torch.float32
    if device.startswith('cuda'):
        dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
    return {'device_map': device, 'dtype': dtype}
