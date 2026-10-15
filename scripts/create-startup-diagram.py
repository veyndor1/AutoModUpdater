"""Produce the editable implementation-flow artifact; render with the Excalidraw skill."""
import json
from pathlib import Path

elements=[]
def base(kind,key,x,y,w,h,stroke='#1e3a5f',fill='transparent'):
    return dict(type=kind,id=key,x=x,y=y,width=w,height=h,angle=0,strokeColor=stroke,
                backgroundColor=fill,fillStyle='solid',strokeWidth=1.5,strokeStyle='solid',
                roughness=0,opacity=100,groupIds=[],seed=100000+len(elements),version=1,
                versionNonce=200000+len(elements),isDeleted=False,boundElements=None,
                updated=1,link=None,locked=False)
def text(key,x,y,value,size=18,color='#374151',width=260):
    height=len(value.splitlines())*size*1.25
    e=base('text',key,x,y,width,height,color)
    e.update(text=value,originalText=value,fontSize=size,fontFamily=3,textAlign='left',
             verticalAlign='top',containerId=None,lineHeight=1.25,autoResize=False)
    elements.append(e)
def shape(kind,key,x,y,w,h,fill,stroke): elements.append(base(kind,key,x,y,w,h,stroke,fill))
def arrow(key,x,y,points,color='#1e3a5f'):
    e=base('arrow',key,x,y,max(p[0] for p in points)-min(p[0] for p in points),max(p[1] for p in points)-min(p[1] for p in points),color)
    e.update(points=points,startBinding=None,endBinding=None,startArrowhead=None,endArrowhead='arrow',elbowed=False)
    elements.append(e)

text('title',40,24,'Minecraft açılış akışı',30,'#1e40af',600)
text('subtitle',40,70,'Doğrulama önce tamamlanır; yeni modlar yeni JVM içinde etkinleşir.',16,'#64748b',900)
shape('ellipse','start',355,115,230,60,'#fed7aa','#c2410c'); text('start-label',378,133,'Oyuncu Minecraft açar',16,width=220)
arrow('to-check',470,175,[[0,0],[0,40]])
text('check',330,222,'Mod + config kontrolü\nİndir → hash doğrula → staging',18,width=330)
arrow('to-result',470,278,[[0,0],[0,35]])
shape('diamond','result',375,315,190,115,'#fef3c7','#b45309'); text('result-label',425,358,'Sonuç?',18,width=110)
arrow('to-error',375,372,[[0,0],[-90,0]],'#b91c1c')
text('error',30,340,'Hata\nTekrar dene / Çıkış',18,'#b91c1c',250)
arrow('retry',90,330,[[0,0],[0,-86],[218,-86]],'#b91c1c'); text('retry-label',98,215,'Önbelleği koru',14,'#64748b',160)
arrow('to-current',565,372,[[0,0],[155,0]])
text('current',735,335,'Değişiklik yok\nOtomatik devam',18,width=250)
arrow('current-game',815,395,[[0,0],[0,220]],'#047857')
arrow('to-choice',470,430,[[0,0],[0,45]])
text('choice',358,482,'Değişiklik var\nTamam / Çıkış',20,width=280)
arrow('exit',350,516,[[0,0],[-170,0],[-170,102]],'#047857')
text('exit-label',20,620,'Çıkış: kod 0\nYeniden açılış yok\nHelper bekleyen kurulumu tamamlar',16,'#047857',315)
arrow('continue',470,536,[[0,0],[0,60]])
shape('diamond','pending',365,600,210,110,'#fef3c7','#b45309'); text('pending-label',405,636,'Yeni mod var?',16,width=160)
arrow('config-only',575,655,[[0,0],[160,0]],'#047857'); text('config-label',608,618,'Yalnız config',14,'#64748b',160)
shape('ellipse','game',735,615,230,80,'#a7f3d0','#047857'); text('game-label',764,645,'Minecraft ana menü',16,width=220)
arrow('fresh',470,710,[[0,0],[0,35]])
text('helper',325,753,'Helper hazır → request yaz → çıkış 0\nMod + config kur → yeni JVM başlat',16,width=390)
arrow('fresh-game',665,778,[[0,0],[185,0],[185,-83]],'#047857')
text('evidence',40,853,'request-<pid>.json: { sessionId, relaunch: true | false }\nProfil kilidi + doğrulanmış staging + config transaction; kimlik bilgileri diske yazılmaz.',15,'#64748b',940)
path=Path('build/design/startup-flow.excalidraw')
path.parent.mkdir(parents=True,exist_ok=True)
path.write_text(json.dumps(dict(type='excalidraw',version=2,source='https://excalidraw.com',elements=elements,appState=dict(viewBackgroundColor='#ffffff',gridSize=None),files={}),ensure_ascii=False),encoding='utf-8')
print(path)
