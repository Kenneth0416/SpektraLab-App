"""Scan visible text for occlusion using the shared Ego driver."""
import argparse
import json
import math
from driver import ROOT, browser_options, browser_kwargs, fresh_run, run_browser

JS = r"""
(t)=>{
 window.__seek(t);
 const op=e=>{let o=1;for(let x=e;x&&x.nodeType===1;x=x.parentElement){const cs=getComputedStyle(x);if(cs.visibility==='hidden'||cs.display==='none')return 0;o*=+cs.opacity}return o};
 const paints=e=>{const tn=e.tagName.toLowerCase();if(tn==='img'||tn==='canvas')return true;
   if(['rect','circle','ellipse','polygon','path','line'].includes(tn)){const cs=getComputedStyle(e);return (cs.fill!=='none'&&+cs.fillOpacity>.3)||false}
   const cs=getComputedStyle(e);const bg=cs.backgroundColor;if(bg&&bg!=='rgba(0, 0, 0, 0)'&&bg!=='transparent'){const m=bg.match(/[\d.]+/g);if(!m||m.length<4||+m[3]>.3)return true}
   if(cs.backgroundImage&&cs.backgroundImage!=='none')return true;
   for(const n of e.childNodes)if(n.nodeType===3&&n.textContent.trim())return true;return false};
 const out=[];
 const subEl=document.getElementById('sub');let subR=null;
 if(subEl.textContent.trim()&&op(subEl)>.3){const r=document.createRange();r.selectNodeContents(subEl);subR=r.getBoundingClientRect()}
 const walker=document.createTreeWalker(document.getElementById('stage'),NodeFilter.SHOW_TEXT);
 let n;while(n=walker.nextNode()){
  const txt=n.textContent.trim();if(!txt)continue;const el=n.parentElement;if(el.id==='sub'||el.closest('#sub'))continue;
  if(el.closest('#stripIn'))continue;
  const o=op(el);if(o<.5)continue;
  const r=document.createRange();r.selectNodeContents(n);
  for(const R of r.getClientRects()){
   if(R.width<4||R.height<4)continue;
   // clipped by write() clip-path? skip partially written text
   const cp=getComputedStyle(el).clipPath;if(cp&&cp!=='none'&&!/inset\(-30% 0(px|%)? -30% -5%\)/.test(cp)&&el.classList.contains('hand'))continue;
   const probs=[];
   if(R.left<0||R.right>1920||R.top<0||R.bottom>1080)probs.push('offscreen');
   if(subR&&!(R.right<subR.left||R.left>subR.right||R.bottom<subR.top||R.top>subR.bottom))probs.push('subtitle');
   const hits=new Set();
   for(const fx of [.12,.5,.88])for(const fy of [.3,.7]){const x=R.left+R.width*fx,y=R.top+R.height*fy;if(x<0||y<0||x>1920||y>1080)continue;
    for(const h of document.elementsFromPoint(x,y)){if(h===el||el.contains(h))break;if(h.contains(el))continue;
     if(h.closest('.ink')||h.id==='sub')continue;if(op(h)<.3)continue;
     if(paints(h)){hits.add((h.id||h.className&&h.className.baseVal!==undefined?h.className.baseVal:h.className||'')+'<'+h.tagName.toLowerCase()+'>'+(h.parentElement&&h.parentElement.id?'@'+h.parentElement.id:''));break}}}
   if(hits.size)probs.push('covered by '+[...hits].join(','));
   // clipped by ancestor overflow hidden
   for(let a=el.parentElement;a&&a.id!=='stage';a=a.parentElement){const cs=getComputedStyle(a);if(cs.overflow==='hidden'){const A=a.getBoundingClientRect();if(R.left<A.left-1||R.right>A.right+1||R.top<A.top-1||R.bottom>A.bottom+1){probs.push('clipped by '+(a.id||a.className));}break}}
   if(probs.length)out.push({txt:txt.slice(0,24),id:el.id||el.className,r:[Math.round(R.left),Math.round(R.top),Math.round(R.right),Math.round(R.bottom)],probs});
  }
 }
 return out}
"""

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--step', type=float, default=.25)
    parser.add_argument('--duration', type=float)
    browser_options(parser)
    args = parser.parse_args()
    duration = args.duration if args.duration is not None else json.loads((ROOT / 'timeline.json').read_text())['duration']
    if not math.isfinite(args.step) or args.step <= 0 or not math.isfinite(duration) or duration <= 0:
        parser.error('step and duration must be positive finite numbers')
    out = fresh_run(args.output_dir or ROOT / 'scratch')
    receipt = run_browser([i * args.step for i in range(math.ceil(duration / args.step))], out,
                          inspect=JS, **browser_kwargs(args))
    (out / 'occlusion.json').write_text(json.dumps(receipt['results'], ensure_ascii=False, indent=2))
    agg = {}
    for frame in receipt['results']:
        t = frame['time']
        for item in frame['items']:
            key = (item['txt'], '; '.join(item['probs']))
            entry = agg.setdefault(key, [t, t, item['r']])
            entry[1] = t
    for (text, problems), (start, end, rect) in sorted(agg.items(), key=lambda kv: kv[1][0]):
        print(f'{start:6.2f}-{end:6.2f}  {text!r:28} {rect}  {problems}')


if __name__ == '__main__':
    main()
