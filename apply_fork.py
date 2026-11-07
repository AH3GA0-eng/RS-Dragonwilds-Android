#!/usr/bin/env python3
"""Conservative source-only overlay of an independent GameNative fork.

Patch prerequisites are checked before any edits are written. No private game
files, tokens, passwords, SDK downloads or original GameNative data are used.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parent
INSERT_LINE = '        DragonwildsBootstrap.installFirstRunDefaults(applicationContext)\n'


def patch_once(text: str, anchor: str, replacement: str, description: str) -> str:
    if replacement in text:
        return text
    matches = text.count(anchor)
    if matches != 1:
        raise ValueError(f'{description}: expected exactly one upstream anchor, found {matches}; refuse patch')
    return text.replace(anchor, replacement, 1)


def prepare(repo: Path, app_id: str) -> dict[Path, str]:
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', app_id):
        raise ValueError(f'Invalid Android applicationId: {app_id!r}')
    if app_id == 'app.gamenative':
        raise ValueError('Refusing to override the installed GameNative applicationId')

    mainfile = repo / 'app/src/main/java/app/gamenative/MainActivity.kt'
    gradle = repo / 'app/build.gradle.kts'
    strings = repo / 'app/src/main/res/values/strings.xml'
    bootstrap = repo / 'app/src/main/java/app/gamenative/DragonwildsBootstrap.kt'
    diagnostics = repo / 'app/src/main/java/app/gamenative/DragonwildsLaunchDiagnostics.kt'
    for file in (mainfile, gradle, strings):
        if not file.is_file():
            raise FileNotFoundError(f'Missing source file {file}; check source tree/revision')

    main = mainfile.read_text(encoding='utf-8')
    main = patch_once(main,
        '        ContainerUtils.setContainerDefaults(applicationContext)\n',
        '        ContainerUtils.setContainerDefaults(applicationContext)\n' + INSERT_LINE,
        'first-run defaults')

    main = patch_once(main,
        '        DragonwildsBootstrap.installFirstRunDefaults(applicationContext)\n',
        '        DragonwildsBootstrap.installFirstRunDefaults(applicationContext)\n' +
        '        DragonwildsLaunchDiagnostics.install(applicationContext)\n',
        'memory diagnostics startup')

    gradle_src = gradle.read_text(encoding='utf-8')
    gradle_src = patch_once(gradle_src,
        'applicationId = "app.gamenative"',
        f'applicationId = "{app_id}"',
        'independent package name')

    # Reuse the existing permanent signing key stored in GitHub Actions.
    # Ordinary CI without that secret may compile but must not publish an APK.
    gradle_src = patch_once(
        gradle_src,
        '    signingConfigs {\n        create("pluvia") {\n',
        '    signingConfigs {\n'
        '        create("dragonwildsStable") {\n'
        '            val password = System.getenv("DRAGONWILDS_SIGNING_PASSWORD")\n'
        '            if (!password.isNullOrBlank()) {\n'
        '                storeFile = rootProject.file("dragonwilds-signing.p12")\n'
        '                storePassword = password\n'
        '                keyAlias = "dragonwilds-mobile"\n'
        '                keyPassword = password\n'
        '            }\n'
        '        }\n'
        '        create("pluvia") {\n',
        'add established Dragonwilds stable signing config',
    )
    gradle_src = patch_once(
        gradle_src,
        '        debug {\n'
        '            isDebuggable = true\n'
        '            isMinifyEnabled = false\n'
        '            isShrinkResources = false\n'
        '            signingConfig = signingConfigs.getByName("debug")\n',
        '        debug {\n'
        '            isDebuggable = true\n'
        '            isMinifyEnabled = false\n'
        '            isShrinkResources = false\n'
        '            signingConfig = signingConfigs.getByName(\n'
        '                if (System.getenv("DRAGONWILDS_SIGNING_PASSWORD").isNullOrBlank()) "debug"\n'
        '                else "dragonwildsStable"\n'
        '            )\n',
        'select established stable signing key',
    )
    gradle_src = patch_once(
        gradle_src,
        '        versionCode = 24\n',
        '        versionCode = 10000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 38)\n',
        'ensure Android update version code increases',
    )

    strings_src = strings.read_text(encoding='utf-8')
    matches = list(re.finditer(r'<string\s+name="app_name">.*?</string>', strings_src, re.DOTALL))
    if len(matches) != 1:
        raise ValueError('strings.xml: expected exactly one app_name string')
    current = matches[0].group(0)
    strings_src = strings_src.replace(current, '<string name="app_name">Dragonwilds Mobile</string>', 1)

    return {
        mainfile: main,
        gradle: gradle_src,
        strings: strings_src,
        bootstrap: (ROOT / 'DragonwildsBootstrap.kt').read_text(encoding='utf-8'),
        diagnostics: (ROOT / 'DragonwildsLaunchDiagnostics.kt').read_text(encoding='utf-8'),
    }


def run() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help='Cloned GameNative source repository root')
    parser.add_argument('--app-id', default='app.dragonwilds.mobile')
    parser.add_argument('--dry-run', action='store_true', help='Validate only, make no changes')
    opts = parser.parse_args()
    repo = opts.source.expanduser().resolve()
    try:
        edits = prepare(repo, opts.app_id)
        for target, content in edits.items():
            if target.exists() and target.read_text(encoding='utf-8') == content:
                print('unchanged', target.relative_to(repo))
            else:
                print('PATCH', target.relative_to(repo))
        if opts.dry_run:
            print('Dry run passed; no files written.')
            return 0
        # Back up originals before any write; atomic rename for each new result.
        for target, content in edits.items():
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.is_file() and target.read_text(encoding='utf-8') == content:
                continue
            backup = target.with_suffix(target.suffix + '.dw-original')
            if target.is_file() and not backup.exists():
                shutil.copy2(target, backup)
            tmp = target.with_suffix(target.suffix + '.dw-temp')
            tmp.write_text(content, encoding='utf-8')
            tmp.replace(target)
        print('Applied source overlay. Build and Android device validation STILL REQUIRED.')
        return 0
    except (ValueError, OSError) as exc:
        print(f'PATCH FAILED SAFELY: {exc}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(run())
