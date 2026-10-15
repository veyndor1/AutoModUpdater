"""Collect release and native preview evidence after the build/preview commands."""
from pathlib import Path
import hashlib
import json
import struct
import xml.etree.ElementTree as ET
import zipfile
import argparse

parser=argparse.ArgumentParser()
parser.add_argument('--prefix',default='delivered')
parser.add_argument('--date',default='2026-10-14')
parser.add_argument('--expected-tests',type=int,default=47)
options=parser.parse_args()

root = Path(__file__).resolve().parent.parent
release = root / 'build/libs/AutoModUpdater-1-release.jar'
totals = dict(tests=0, failures=0, errors=0, skipped=0)
for folder in ['test', 'verifyRelease']:
    reports = list((root / 'build/test-results' / folder).glob('TEST-*.xml'))
    assert reports, folder
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
assert totals['tests'] == options.expected_tests and not any(totals[k] for k in ['failures', 'errors', 'skipped'])
with zipfile.ZipFile(release) as archive:
    for name in ['day.gif', 'night.gif', 'day.png', 'night.png', 'SOURCES.md']:
        assert archive.read('updater/' + name) == (root / 'src/main/resources/updater' / name).read_bytes(), name
scales = {}
for scale in ['1', '1.25', '1.5']:
    folder = root / 'build/design' / (options.prefix + '-' + scale)
    sizes = {}
    for state in ['night-downloading', 'light-downloading', 'light-ready', 'night-ready', 'night-settings', 'light-settings', 'compact-ready', 'compact-error', 'night-error']:
        data = (folder / (state + '.png')).read_bytes()
        assert data[:8] == b'\x89PNG\r\n\x1a\n'
        sizes[state] = struct.unpack('>II', data[16:24])
    assert (folder / 'reduced-motion-a.png').read_bytes() == (folder / 'reduced-motion-b.png').read_bytes()
    if options.prefix == 'feedback':
        for state in ['light-completion', 'continue-confirmed', 'early-continue-confirmed']:
            assert (folder / (state + '.png')).is_file(), state
        assert (folder/'reduced-completion-a.png').read_bytes() == (folder/'reduced-completion-b.png').read_bytes()
        assert (folder/'completion-chime.wav').stat().st_size > 80_000
    scales[scale] = sizes
result = {'date': options.date, 'tests': totals, 'releaseSha256': hashlib.sha256(release.read_bytes()).hexdigest(),
          'releaseBytes': release.stat().st_size, 'bundledAssetsMatchSource': True,
          'previewScales': scales, 'previewMethod': 'Offscreen real Swing components; production animation tick driven by UiPreviewFixture.',
          'checks': ['GIF palette fidelity', 'both themes', 'interrupted quick theme transition', 'synchronized theme controls', 'reduced-motion stillness', 'continue', 'retry', 'window close', 'double-click'],
          'limitations': ['Full Forge launch scenarios were not rerun in this visual-only pass.', 'Independent review agent was unavailable because of usage limits; final review was performed in the parent session.']}
if options.prefix == 'feedback':
    result['checks'] += ['transparent click-through completion overlay clears after 3 seconds', 'once-only completion cue; no success cue on error/unchanged release', 'minimum 1-second action confirmation', 'early Continue waits for completion', 'static 3-second notification with reduced motion', 'soft chime PCM envelope/peak']
    result['limitations']=['Captures render actual Swing components offscreen; playback uses an injected cue counter in the fixture.', 'No physical audio-device listening test or full Forge launch rerun in this pass.']
output = root / 'build/design' / ('feedback-verification.json' if options.prefix == 'feedback' else 'woodland-verification.json')
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'tests': totals, 'sha256': result['releaseSha256'], 'scales': list(scales), 'assetsMatch': True}, indent=2))
