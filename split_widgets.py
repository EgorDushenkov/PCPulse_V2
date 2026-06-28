# -*- coding: utf-8 -*-
import re
import os

with open('c:/Users/Егор/Desktop/samsa/Android/app/src/main/java/com/example/pc/DashboardWidgets.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# get package and imports
header_match = re.match(r'(.*?)(?=fun |class )', content, re.DOTALL)
header = header_match.group(1) if header_match else ''

# match global funs
funs = re.findall(r'(fun\s+[\w\.<>]+.*?)(?=\nfun |\nclass )', content, re.DOTALL)
if funs:
    utils = header + '\n' + '\n\n'.join(funs)
    with open('c:/Users/Егор/Desktop/samsa/Android/app/src/main/java/com/example/pc/ui/widgets/WidgetUtils.kt', 'w', encoding='utf-8') as f:
        f.write(utils.replace('package com.example.pc', 'package com.example.pc.ui.widgets'))

# match classes
classes = re.finditer(r'(class\s+(\w+).*?)(?=\nclass |\Z)', content, re.DOTALL)
for match in classes:
    cls_code = match.group(1)
    cls_name = match.group(2)
    with open(f'c:/Users/Егор/Desktop/samsa/Android/app/src/main/java/com/example/pc/ui/widgets/{cls_name}.kt', 'w', encoding='utf-8') as f:
        full_code = header + '\n' + cls_code
        # fix the catch block empty error (swallow catch)
        full_code = full_code.replace('catch (e: Exception) {}', 'catch (e: Exception) { android.util.Log.e("Widget", "Error", e) }')
        full_code = full_code.replace('package com.example.pc', 'package com.example.pc.ui.widgets')
        f.write(full_code)

os.remove('c:/Users/Егор/Desktop/samsa/Android/app/src/main/java/com/example/pc/DashboardWidgets.kt')
