import os
import re

files_to_update = [
    "online-compiler.html",
    "code-analyzer.html",
    "global.html",
    "secret.html",
    "dashboard.html",
    "vm-compiler.html",
    "vm-history.html",
    "vm-leaderboard.html",
    "vm-problems.html",
    "voidmain.html"
]

for file in files_to_update:
    if not os.path.exists(file):
        continue
    
    with open(file, 'r', encoding='utf-8') as f:
        content = f.read()

    original = content

    # Upgrade font size of DevAssist AI to 20px
    content = re.sub(
        r'(font-size:\s*)18px(.*?)>DevAssist AI</span>',
        r'\g<1>20px\2>DevAssist AI</span>',
        content,
        flags=re.IGNORECASE | re.DOTALL
    )

    # For inline img style
    content = re.sub(
        r'<img[^>]*src=[\'"]img/logo\.png[\'"][^>]*style=[\'"][^\'"]*[\'"][^>]*>',
        r'<img src="img/logo.png" style="width: 80px; height: 80px; object-fit: contain; display: block; filter: drop-shadow(0 0 10px rgba(124, 58, 237, 0.4)); margin-top: 12px; margin-right: -16px; margin-left: -10px;" />',
        content,
        flags=re.IGNORECASE
    )

    # For CSS classes (like in online-compiler and dashboard)
    # online-compiler img:
    content = re.sub(
        r'\.topbar-brand\s*img\s*\{[^}]*\}',
        r'.topbar-brand img { width: 80px; height: 80px; object-fit: contain; filter: drop-shadow(0 0 10px rgba(124, 58, 237, 0.4)); margin-top: 12px; margin-right: -16px; margin-left: -10px; }',
        content
    )
    # online-compiler .topbar-brand gap 
    content = re.sub(
        r'(\.topbar-brand\s*\{.*?gap:\s*)[0-9]+px(.*?)\}',
        r'\g<1>0px\2}',
        content
    )

    # dashboard .brand img (it might not have it directly, but let's replace if exists)
    content = re.sub(
        r'\.brand\s*img\s*\{[^}]*\}',
        r'.brand img { width: 80px; height: 80px; object-fit: contain; filter: drop-shadow(0 0 10px rgba(124, 58, 237, 0.4)); margin-top: 12px; margin-right: -16px; margin-left: -10px; }',
        content
    )
    # dashboard .brand gap
    content = re.sub(
        r'(\.brand\s*\{.*?gap:\s*)[0-9]+px(.*?)\}',
        r'\g<1>0px\2}',
        content
    )
    
    # Inline style gap replacement on brand wrappers
    content = re.sub(
        r'(<div[^>]*class=[\'"]brand[\'"][^>]*style="[^"]*gap:\s*)[0-9]+px([^"]*")',
        r'\g<1>0px\2',
        content,
        flags=re.IGNORECASE
    )

    if content != original:
        with open(file, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"Updated {file}")
    else:
        print(f"No changes matched in {file}")

print("Done")
