"""v3 = v1 photographer visuals, retimed to v3 narration, ad copy removed, try-it end card."""
import os, re
H = os.path.dirname(os.path.abspath(__file__))
s = open(os.path.join(H, '..', 'v1', 'film.html')).read()

def R(a, b, n=1):
    global s
    c = s.count(a)
    assert c == n, (a[:70], c)
    s = s.replace(a, b)

# ---------- copy ----------
R('银盐工坊 · 宣传片', '银盐工坊 · 把照片重新冲一遍')
R('PHONE JPEG · ISP 锐化 + 降噪 + 色调映射', '手机直出的 JPEG')
R('+ FILM LUT', '+ 胶片滤镜')
R('>套滤镜，<', '>颜色挺像，<')
R('>不等于换了一卷胶片。<', '>可光不对。<')
R('<b>01</b>先曝光，再显影', '<b>01</b>胶片是慢慢变浓的')
R('原片 · 数码 JPEG', '原片')
R('负片（示意）· 曝光在 KODAK GOLD 200 上', '曝光在 KODAK GOLD 200 上 · 负片（示意）')
R('冲印 · GOLD 200 × ENDURA PREMIER', '冲印到 ENDURA 相纸上')
R('>像素 → 光谱<', '>先把颜色还原成光<')
R('KODAK GOLD 200 · 特性曲线（引擎数据）', 'KODAK GOLD 200 · 特性曲线')
R('曝光量 log E →', '曝光 →')
R('>密度 D<', '>密度<')
R('肩部：高光慢慢收', '亮处慢慢收住')
R('趾部：暗部有厚度', '暗处还有层次')
R('<b>02</b>光晕：最先染红的是红敏层', '<b>02</b>路灯边上那圈红')
R('强光 → 穿透三层乳剂 → 片基背面反射', '光穿透胶片 → 从背面弹回来')
R('反射光，先打在红敏层', '把红色那层又晒了一遍')
R('<b>03</b>DIR 耦合剂：三层乳剂互相抑制', '<b>03</b>三层颜色，互相压一压')
R('耦合剂 关<', '没有这一步<')
R('耦合剂 开 · PORTRA 400', '有这一步 · PORTRA 400')
R('颜色更纯，边缘更利', '颜色更干净，边更利落')
R('显影抑制剂在药水里扩散 20–200 µm', '显影时，每层会压住旁边的层')
R('互相压一下，色就分开了', '看巴士这块黄')
R('<b>04</b>颗粒长在密度里', '<b>04</b>颗粒是显出来的')
R('手机：涂抹 →', '原片 →')
R('← 银盐：颗颗分明', '← 银盐一颗一颗')
R('按每平方微米的银盐数量随机显影', '亮处、暗处，长得不一样')
R('密度越高，颗粒越密', '不是后期撒的噪点')
R('<b>05</b>放大机 × 相纸', '<b>05</b>放大到相纸上')
R('>偏暖<', '>暖一点<')
R('>反差更硬<', '>硬一点<')
R('全部样片由本引擎实际冲印', '真照片 · 冲完没有再修图')
R('把暗房装进取景器', '拍的时候就看得到')
R('CameraX 预览流 → OpenGL ES 3.0 胶片×相纸管线 → 实时上屏', '取景器里，就是冲好的样子')
R("const CH=['光谱曝光 · 特性曲线','光晕 · 柔焦镜','DIR 耦合剂','三子层银盐颗粒','放大机 × 相纸'];",
  "const CH=['曝光 · 特性曲线','路灯的红晕','三层互相压一压','银盐颗粒','放大到相纸'];")
R('绕过 ISP，直读 RAW', '不让手机替你修图')
R("const ISP=['锐化','涂抹降噪','局部色调映射','AI 补细节'];", "const ISP=['锐化','涂抹降噪','自动提亮','AI 补细节'];")
R('RAW×3 对齐合成<br><span style="color:#C9A99A;font-size:17px">亚像素对齐 · 防鬼影 · 线性 DNG</span>',
  '传感器的原始数据<br><span style="color:#C9A99A;font-size:17px">暗的时候连拍几张，叠成一张</span>')
R('长边最高 12000 px · 保留 EXIF', '保留拍摄信息')
R('DCIM/Silvercam', '留着，以后还能重冲')

R('<div id="s6cc" class="lbl" style="left:140px;top:262px;font-size:20px">', '<div id="s6cc" class="lbl" style="left:420px;top:318px;font-size:20px">')
R("show($('s6cc'),t,61.4,.4,6);", "show($('s6cc'),t,61.4,.4,6,68.0,.4);")
R('<div id="s2chartT" class="lbl" style="left:1060px;top:300px">', '<div id="s2chartT" class="lbl" style="left:1060px;top:270px">')
R('<div id="s2n1" class="hand" style="left:1420px;top:440px;font-size:46px">', '<div id="s2n1" class="hand" style="left:1490px;top:466px;font-size:46px">')
R('<div id="s2n2" class="hand" style="left:1260px;top:700px;font-size:46px">', '<div id="s2n2" class="hand" style="left:1370px;top:752px;font-size:46px">')
R("b.style.top=(20+(i*14)*c)+'px'", "b.style.top=(20+(i*14)*c)+'px';b.firstElementChild.style.opacity=(1-c).toFixed(3)")
# ---------- S10: try-it card instead of brand end card ----------
a = s.index('<!-- S10 -->'); b = s.index('<!-- subtitles -->')
s = s[:a] + '''<!-- S10 try it -->
<div id="s10" class="scene">
 <div id="t1" class="abs serif" style="left:170px;top:140px;font-size:64px;color:#F5DDD2">还很早期。</div>
 <div id="t2" class="abs" style="left:172px;top:240px;font-size:28px;line-height:1.8;color:#C9A99A">只在几台手机上试过；RAW 要看你的手机支不支持。<br>安卓 10 以上，可在项目主页下载 APK 安装。</div>
 <div id="t4" class="hand" style="left:180px;top:396px;font-size:62px;color:#FF6A50">拍几张，哪里不像胶片，告诉我</div>
 <div id="t5" class="abs" style="left:182px;top:500px;font-size:22px;color:#C9A99A">在仓库里提个 issue，或者直接留言都行</div>
 <div id="cr" class="abs" style="left:170px;top:586px;width:1120px;font-size:17px;line-height:1.85;color:#9C7F74">冲印算法来自开源项目 spektrafilm（Andrea Volpato），参考 SpektraLab（James Qiu）<br>非官方、独立的下游项目 · 样片原图：影视飓风素材库<br>Kodak、Fujifilm 等胶片与相纸名称仅作描述性参考</div>
 <div id="t6" class="card" style="left:1380px;top:120px;padding:12px 12px 52px;transform-origin:50% 0"><div class="ph" style="width:330px;height:452px"><img src="../samples/night_vision500t_2383.jpg"></div><div class="abs mono" style="left:14px;bottom:14px;font-size:14px;color:#6B675E">VISION3 500T × 2383</div></div>
 <div id="t3" class="abs" style="left:170px;top:742px;width:1580px;height:150px;border:1px solid #7a3a30;background:rgba(20,10,8,.7);display:flex">
  <div style="flex:1;padding:24px 36px"><div class="mono" style="font-size:15px;color:#FF6A50;letter-spacing:.16em">项目主页</div>
  <div class="mono" style="font-size:32px;color:#F5DDD2;margin-top:16px;white-space:nowrap">silvercam.convert-md.com</div></div>
  <div style="width:1px;background:#7a3a30;margin:22px 0"></div>
  <div style="flex:1.35;padding:24px 36px"><div class="mono" style="font-size:15px;color:#FF6A50;letter-spacing:.16em">GITHUB · 免费开源</div>
  <div class="mono" style="font-size:32px;color:#F5DDD2;margin-top:16px;white-space:nowrap">github.com/Kenneth0416/SpektraLab-App</div></div>
 </div>
 <svg class="ink" id="ink-s10"></svg>
</div>

''' + s[b:]
R("mark('ink-s10',handLine(700,566,1220,560,41),114.2,114.8,{strokeWidth:7});",
  "mark('ink-s10',handLine(186,482,1090,476,41),113.9,114.6,{stroke:'#FF6A50',strokeWidth:5});")

# ---------- retime build-time marks ----------
R("mark('ink-s1',handEllipse(694,612,46,40,3),8.0,8.7);", "mark('ink-s1',handEllipse(694,612,46,40,3),8.6,9.3);")
R("mark('ink-s1',handLine(744,604,918,592,4),8.6,8.9,", "mark('ink-s1',handLine(744,604,918,592,4),9.2,9.5,")
R("300+104,90,46,7),21.9,22.6)", "300+104,90,46,7),16.2,16.9)")
R("300+478,80,34,8),23.3,24.0)", "300+478,80,34,8),18.3,19.0)")
R("{stroke:'#FFDCC0'}", "{stroke:'#FFDCC0'}")
R("304+392,62,52,11),35.0,35.6", "304+392,62,52,11),30.0,30.6")
R("handEllipse(970,420,120,84,13),43.0,43.7", "handEllipse(970,420,120,84,13),44.1,44.8")
R("at:52.4+ri*.7+u*2.4+r()*1.2", "at:50.0+ri*.7+u*2.4+r()*1.2")
R("handEllipse(988+185,360+107,215,140,17),72.6,73.3", "handEllipse(988+185,360+100,212,124,17),72.3,73.0")
R("const G0=76.6,GD=2.36;", "const G0=76.6,GD=2.04;")
R("99.7+i*.55,100.1+i*.55", "99.0+i*.4,99.4+i*.4")
R("12.6,13.2,{strokeWidth:5}", "12.6,13.2,{strokeWidth:5}")

# ---------- retime render(t) ----------
D = 87.0  # lights off
R("else if(t<89.7)off=1-K(t,.7,.95);\n else if(t<89.76)off=.45;else if(t<89.82)off=.15;else off=K(t,89.82,89.9);",
  f"else if(t<{D})off=1-K(t,.7,.95);\n else if(t<{D+.06})off=.45;else if(t<{D+.12})off=.15;else off=K(t,{D+.12},{D+.2});")
R("$('dim').style.opacity=K(t,89.7,90.1);$('safe').style.opacity=K(t,90.0,91.2)", f"$('dim').style.opacity=K(t,{D},{D+.4});$('safe').style.opacity=K(t,{D+.3},{D+1.5})")
R("const stepAt=[25.4,37.4,47.6,60.3,76.4]", "const stepAt=[25.6,37.6,47.6,60.2,76.4]")
R("$('strip').style.opacity=t<11.4?0:1-K(t,89.7,90.2);", f"$('strip').style.opacity=t<11.4?0:1-K(t,{D},{D+.5});")
R("$('stripInk').style.opacity=1-K(t,89.7,90.2);", f"$('stripInk').style.opacity=1-K(t,{D},{D+.5});")
# S1
R("show($('s1t1'),t,6.8,.6,16);show($('s1t2'),t,7.4,.6,16);write($('s1n1'),t,8.8,.9);write($('s1n2'),t,9.6,.7)",
  "show($('s1t1'),t,3.6,.6,16);show($('s1t2'),t,8.1,.6,16);write($('s1n1'),t,9.4,.9);write($('s1n2'),t,10.1,.7)")
# S2
R("const n=K(t,13.8,14.9,eio),q=K(t,20.4,21.5,eio);", "const n=K(t,12.6,13.7,eio),q=K(t,21.2,22.3,eio);")
R("$('s2l0').style.opacity=1-K(t,13.8,14.2);$('s2l1').style.opacity=K(t,14.6,15.0)*(1-K(t,20.4,20.8));$('s2l2').style.opacity=K(t,21.2,21.6);",
  "$('s2l0').style.opacity=1-K(t,12.6,13.0);$('s2l1').style.opacity=K(t,13.4,13.8)*(1-K(t,21.2,21.6));$('s2l2').style.opacity=K(t,22.0,22.4);")
R("const sp=K(t,16.4,17.4,x=>x);$('s2spec').style.clipPath=`inset(0 ${(1-sp)*100}% 0 0)`;show($('s2specL'),t,17.0,.4,6);write($('s2specH'),t,17.3,.7);",
  "const sp=K(t,13.2,14.2,x=>x);$('s2spec').style.clipPath=`inset(0 ${(1-sp)*100}% 0 0)`;show($('s2specL'),t,13.8,.4,6);write($('s2specH'),t,14.0,.7);")
R("show($('s2chartT'),t,17.6,.4,6);", "show($('s2chartT'),t,14.2,.4,6);")
R("K(t,18.0+i*.25,20.6+i*.25,eio)", "K(t,14.4+i*.2,16.2+i*.2,eio)")
R("write($('s2n1'),t,22.4,.8);write($('s2n2'),t,23.9,.8)", "write($('s2n1'),t,16.6,.8);write($('s2n2'),t,18.7,.8)")
# S3
R("const h=K(t,32.4,33.4,eio),f=K(t,34.3,35.5,eio);", "const h=K(t,28.8,29.8,eio),f=K(t,35.6,36.6,eio);")
R("$('s3c1').style.opacity=K(t,32.6,33.0);$('s3c0').style.opacity=1-K(t,34.8,35.2);", "$('s3c1').style.opacity=K(t,29.0,29.4);$('s3c0').style.opacity=1-K(t,36.0,36.4);")
R("(1-K(t,28.0,29.4,x=>x))", "(1-K(t,31.6,32.8,x=>x))")
R("(1-K(t,29.9,30.9,x=>x)).toFixed(4);$('s3b2').style.strokeDashoffset=(1-K(t,30.0,31.0,x=>x))",
  "(1-K(t,33.0,33.9,x=>x)).toFixed(4);$('s3b2').style.strokeDashoffset=(1-K(t,33.1,34.0,x=>x))")
R("const gl=K(t,31.9,32.8);", "const gl=K(t,34.4,35.2);")
R("$('s3xl').setAttribute('opacity',K(t,28.6,29.2).toFixed(3));write($('s3n1'),t,32.9,1.0);",
  "$('s3xl').setAttribute('opacity',K(t,31.8,32.4).toFixed(3));write($('s3n1'),t,34.9,1.0);")
R("write(n2,t,35.4,.8)", "write(n2,t,30.4,.8)")
# S4
R("show($('s4b'),t,42.3,.5,10);show($('s4lb'),t,42.5,.4,6);", "show($('s4b'),t,41.5,.5,10);show($('s4lb'),t,41.7,.4,6);")
R("K(t,39.2+i*.55,39.9+i*.55,x=>x)).toFixed(4);a.style.opacity=K(t,39.2+i*.55,39.3+i*.55)",
  "K(t,39.4+i*.4,40.1+i*.4,x=>x)).toFixed(4);a.style.opacity=K(t,39.4+i*.4,39.5+i*.4)")
R("$('s4same').setAttribute('opacity',K(t,41.8,42.2).toFixed(3));", "$('s4same').setAttribute('opacity',K(t,41.2,41.6).toFixed(3));")
R("show($('s4m'),t,41.0,.4,6);write($('s4n0'),t,43.8,.8);write($('s4n1'),t,45.0,.9)",
  "show($('s4m'),t,40.4,.4,6);write($('s4n0'),t,42.4,.8);write($('s4n1'),t,44.4,.9)")
# S5
R("const l=K(t,48.6,49.8,eio);const cx=760+(1-l)*-900", "const l=K(t,55.4,56.6,eio);const cx=760+(1-l)*-1500")
R("write($('s5h1'),t,50.0,.6);write($('s5h2'),t,50.6,.7);", "write($('s5h1'),t,56.6,.6);write($('s5h2'),t,57.2,.7);")
R("show($('s5rows'),t,50.3,.5,8);", "show($('s5rows'),t,49.6,.5,8);")
R("show($('s5m'),t,56.4,.4,6);write($('s5n'),t,57.2,.9)", "show($('s5m'),t,51.8,.4,6);write($('s5n'),t,58.2,.9)")
# S6
R("K(t,63.0,64.6,x=>x)*6", "K(t,65.4,66.6,x=>x)*6")
R("const ang=K(t,63.0,64.6)*120;", "const ang=K(t,65.4,66.6)*120;")
R("K(t,65.3,66.1)*(.92", "K(t,66.6,67.2)*(.92")
R("const p=K(t,61.6,62.3),o=1-K(t,67.0,67.6);", "const p=K(t,61.6,62.3),o=1-K(t,67.6,68.2);")
R("(1-.06*K(t,67.0,67.6))", "(1-.06*K(t,67.6,68.2))")
R("show($('s6neg'),t,67.4,.4,6);", "show($('s6neg'),t,68.0,.4,6);")
R("[[68.4,69.6],[71.1,72.2],[73.5,74.6]]", "[[69.0,71.4],[69.8,72.3],[70.6,73.3]]")
# S7
R("scene($('s7'),t,76.45,88.6,.35);", f"scene($('s7'),t,76.45,{D+.2},.35);")
# S8 phone & chips
R("const r=K(t,90.3,91.5);const out=K(t,111.9,112.6);", f"const r=K(t,{D+.6},{D+1.8});const out=K(t,106.4,107.1);")
R("scene($('s8'),t,90.4,99.1,.4);", f"scene($('s8'),t,{D+.7},96.6,.4);")
R("show($('s8title'),t,91.6,.6,12);show($('s8sub'),t,92.2,.5,8);", "show($('s8title'),t,88.3,.6,12);show($('s8sub'),t,89.0,.5,8);")
R("const a=92.6+i*.55;const p=K(t,a,a+.3);c.style.opacity=K(t,92.3+i*.1,92.6+i*.1);", "const a=90.4+i*.45;const p=K(t,a,a+.3);c.style.opacity=K(t,89.6+i*.1,89.9+i*.1);")
R("$('s8line').style.strokeDashoffset=(1-K(t,95.2,95.8,x=>x))", "$('s8line').style.strokeDashoffset=(1-K(t,93.0,93.6,x=>x))")
R("const w=K(t,95.6,97.4,eio);", "const w=K(t,93.6,95.4,eio);")
# S9
R("scene($('s9'),t,98.9,112.5,.4);", "scene($('s9'),t,96.7,107.0,.4);")
R("show($('s9title'),t,99.0,.6,12);const ispOut=1-K(t,107.2,107.7);", "show($('s9title'),t,96.9,.6,12);const ispOut=1-K(t,101.0,101.5);")
R("$('isp').style.opacity=K(t,99.1,99.5)*ispOut;", "$('isp').style.opacity=K(t,98.6,99.0)*ispOut;")
R("const p=K(t,101.9+i*.25,102.3+i*.25);const c=K(t,103.0,104.0,eio);", "const p=K(t,97.2+i*.25,97.6+i*.25);const c=K(t,98.0,98.8,eio);")
R("$('rawL').style.opacity=K(t,104.0,104.4)*ispOut;", "$('rawL').style.opacity=K(t,98.6,99.0)*ispOut;")
R("const press=K(t,105.25,105.35)*(1-K(t,105.45,105.6));", "const press=K(t,101.35,101.45)*(1-K(t,101.55,101.7));")
R("$('flash').style.opacity=K(t,105.3,105.33)*(1-K(t,105.33,105.5));", "$('flash').style.opacity=K(t,101.4,101.43)*(1-K(t,101.43,101.6));")
R("$('gg').style.opacity=K(t,105.4,105.6)*(1-K(t,107.0,107.3));", "$('gg').style.opacity=K(t,101.5,101.7)*(1-K(t,102.9,103.2));")
R("const lv=K(t,106.0,106.9,eio)*(1-K(t,107.0,107.25,eo));", "const lv=K(t,101.95,102.75,eio)*(1-K(t,102.85,103.1,eo));")
R("$('cnt').textContent=t<106.9?'8':'9';", "$('cnt').textContent=t<102.75?'8':'9';")
R("if(t<105.9){fo=K(t,104.9,105.1)*(1-K(t,105.6,105.8));fx=163;fy=746}else{fo=K(t,105.9,106.0)*(1-K(t,106.95,107.15));fx=226+K(t,106.0,106.9,eio)*96;fy=648}",
  "if(t<101.85){fo=K(t,101.0,101.2)*(1-K(t,101.6,101.8));fx=163;fy=746}else{fo=K(t,101.85,101.95)*(1-K(t,102.8,103.0));fx=226+K(t,101.95,102.75,eio)*96;fy=648}")
R("[[107.9,108.7,170,-4],[109.5,110.3,530,3]]", "[[102.6,103.4,170,-4],[104.0,104.8,530,3]]")
# S10
a = s.index(" // S10 112.4-119.5"); b = s.index(" // subtitles")
s = s[:a] + ''' // S10 try-it 107-119.5
 scene($('s10'),t,107.0,130,.4);
 {show($('t1'),t,107.4,.6,12);show($('t2'),t,108.6,.6,8);show($('t3'),t,110.8,.6,10);write($('t4'),t,113.0,1.0);show($('t5'),t,114.6,.5,6);show($('cr'),t,115.4,.8,6);{const p=K(t,108.0,108.9);const e=$('t6');e.style.opacity=p;e.style.transform=`translateY(${((1-p)*-40).toFixed(1)}px) rotate(${(2.2+(1-p)*3).toFixed(2)}deg)`}}

''' + s[b:]
R("let txt='',so=0;if(t<112.5){for(", "let txt='',so=0;{for(")
R("fill-opacity',(.16+gl*.3)", "fill-opacity',(.16+gl*.3)")

left = re.findall(r'银盐工坊。|每一次快门|SILVERCAM ·|ISP|CameraX|OpenGL|引擎', s)
print('leftover:', left)
open(os.path.join(H, 'film.html'), 'w').write(s)
print('ok', len(s))
