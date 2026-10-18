// Faux D1 au-dessus de node:sqlite + stubs des helpers du Worker, pour tester rp-names.js tel quel.
import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
const sqlite = new DatabaseSync(':memory:');
sqlite.exec(`CREATE TABLE users (discord_id TEXT, pseudo TEXT, minecraft_uuid TEXT)`);
sqlite.exec(`INSERT INTO users VALUES ('111','Alice','AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE')`);
class Stmt { constructor(sql){this.sql=sql;this.b=[];} bind(...a){const s=new Stmt(this.sql);s.b=a;return s;}
  async all(){return {results: sqlite.prepare(this.sql).all(...this.b)};}
  async first(){return sqlite.prepare(this.sql).get(...this.b)||null;}
  async run(){const r=sqlite.prepare(this.sql).run(...this.b);return {meta:{changes:r.changes}};} }
const db = { prepare:(s)=>new Stmt(s), async batch(list){ for(const s of list) await s.run(); return []; } };
const env = { relinkdb: db };

const code = fs.readFileSync('rp-names.js','utf8');
const helpers = `
const json=(d,s=200)=>({data:d,status:s});
const readJson=async(r)=>r.body;
const sanitizeText=(v,m)=>typeof v==='string'?v.replace(/[\\u0000-\\u001f\\u007f]/g,'').trim().slice(0,m):'';
const escapeLike=(s)=>s.replace(/[\\\\%_]/g,c=>'\\\\'+c);
const clampInt=(v,d,mn,mx)=>{const n=parseInt(v,10);return Number.isFinite(n)?Math.min(Math.max(n,mn),mx):d;};
const runInChunks=async(env,st)=>{for(let i=0;i<st.length;i+=100)await env.relinkdb.batch(st.slice(i,i+100));};
const internalError=(e)=>{console.error(e);return {data:'ERR',status:500};};
let KEY_OK={};
const checkServiceKey=async(req,env,scope)=>KEY_OK[scope]===req.key;
const checkPermissionOrAdmin=async(req)=>req.admin===true;
`;
const mod = new Function(helpers + code + '\nreturn {handleRpNamesPost,handleRpNamesGet,handleAdminRpNames,foldRpName,setKeys:(k)=>{KEY_OK=k;}};')();
mod.setKeys({'rp.write':'W','rp.read':'R','mc.register':'M','mc.permissions.read':'M'});
const U1='11111111-2222-3333-4444-555555555555', AL='aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
const url=(q)=>({url:'https://x/mc/rp/names?'+q});
let fails=0; const ok=(c,m)=>{console.log((c?'PASS ':'FAIL ')+m); if(!c)fails++;};

// auth
ok((await mod.handleRpNamesPost({key:'nope',body:{}},env,'')).status===401,'POST sans bonne clé → 401');
ok((await mod.handleRpNamesPost({key:'R',body:{entries:[]}},env,'')).status===401,'POST avec clé de LECTURE → 401');
// validation
ok((await mod.handleRpNamesPost({key:'W',body:{entries:[]}},env,'')).status===400,'entries vide → 400');
ok((await mod.handleRpNamesPost({key:'W',body:{entries:Array(51).fill({})}},env,'')).status===413,'51 entrées → 413');
ok((await mod.handleRpNamesPost({key:'W',body:{entries:[{bad:1}]}},env,'')).status===400,'entrée invalide → 400');
// insertion
const e1={event_id:'evt-0001-aaaa',mc_uuid:U1,mc_username:'Steve',kind:'name',rp_name:'Élodie',previous_rp_name:'Jean',source:'player',created_at:'2026-10-17T14:03:22.123Z'};
const e2={event_id:'evt-0002-bbbb',mc_uuid:AL,mc_username:'Alice',kind:'name',rp_name:'Marc',source:'staff',set_by:'Admin',created_at:'2026-10-17T15:00:00Z'};
const e3={event_id:'evt-0003-cccc',mc_uuid:U1,mc_username:'Steve',kind:'alias',rp_name:'Le Borgne',source:'alias',shown_to_uuid:AL,shown_to_username:'Alice',created_at:'2026-10-17T16:00:00Z'};
const eBadAlias={event_id:'evt-0004-dddd',mc_uuid:U1,kind:'alias',rp_name:'X',source:'alias'}; // sans shown_to_uuid
let r=await mod.handleRpNamesPost({key:'W',body:{entries:[e1,e2,e3,eBadAlias]}},env,'');
ok(r.status===200&&r.data.received===3&&r.data.rejected===1,'lot mixte : 3 reçues, 1 rejetée');
// idempotence (le mod renvoie le même lot)
r=await mod.handleRpNamesPost({key:'W',body:{entries:[e1,e2,e3]}},env,'');
ok(r.status===200,'renvoi du même lot → 200');
ok(sqlite.prepare('SELECT COUNT(*) n FROM rp_name_history').get().n===3,'aucun doublon après renvoi (event_id UNIQUE)');
ok(sqlite.prepare("SELECT mc_uuid FROM rp_name_history WHERE event_id='evt-0001-aaaa'").get().mc_uuid==='11111111222233334444555555555555','UUID normalisé (sans tirets, minuscules)');
// lecture
r=await mod.handleRpNamesGet({key:'R',...url('')},env,'');
ok(r.status===400,'GET sans filtre → 400 (pas de dump complet)');
r=await mod.handleRpNamesGet({key:'W',...url('uuid='+U1)},env,'');
ok(r.status===401,'GET avec clé d\'ÉCRITURE seule → 401');
r=await mod.handleRpNamesGet({key:'R',...url('uuid='+U1)},env,'');
ok(r.status===200&&r.data.entries.length===2&&r.data.entries[0].kind==='alias','GET par uuid : 2 entrées, plus récente d\'abord');
ok(!('discord_id' in r.data.entries[0]),'la clé du serveur de jeu ne voit pas le compte Discord');
r=await mod.handleRpNamesGet({key:'R',...url('q=elodie')},env,'');
ok(r.data.entries.length===1&&r.data.entries[0].rp_name==='Élodie','recherche « elodie » trouve « Élodie » (accents/casse ignorés)');
r=await mod.handleRpNamesGet({key:'R',...url('q=le_borgne')},env,'');
ok(r.data.entries.length===1,'recherche « le_borgne » (underscore = espace)');
r=await mod.handleRpNamesGet({key:'R',...url('q=%25')},env,'');
ok(r.data.entries.length===0,'« % » n\'est pas un joker');
r=await mod.handleRpNamesGet({key:'R',...url('uuid=zzz')},env,'');
ok(r.status===400,'uuid invalide → 400');
r=await mod.handleRpNamesGet({key:'R',...url('limit=1')},env,'');
ok(r.status===200&&r.data.entries.length===1,'limit=1 (test /r worker status) → 200');
r=await mod.handleRpNamesGet({key:'R',...url('uuid='+U1+'&kind=alias')},env,'');
ok(r.data.entries.length===1&&r.data.entries[0].shown_to_username==='Alice','filtre kind=alias');
// clé d'appairage (mc-<slug>) : mc.register écrit, mc.permissions.read lit
const eM={event_id:'evt-0005-eeee',mc_uuid:U1,mc_username:'Steve',kind:'name',rp_name:'Zoé',source:'player',created_at:'2026-10-17T17:00:00Z'};
r=await mod.handleRpNamesPost({key:'M',body:{entries:[eM]}},env,'');
ok(r.status===200&&r.data.received===1,'clé d\'appairage (mc.register) peut écrire');
r=await mod.handleRpNamesGet({key:'M',...url('q=zoe')},env,'');
ok(r.status===200&&r.data.entries.length===1,'clé d\'appairage (mc.permissions.read) peut lire');
// admin
r=await mod.handleAdminRpNames({admin:false,url:'https://x/admin/rp-names'},env,'');
ok(r.status===401,'admin sans droit → 401');
r=await mod.handleAdminRpNames({admin:true,url:'https://x/admin/rp-names?uuid='+AL},env,'');
ok(r.status===200&&r.data.entries.length===1&&r.data.entries[0].discord_id==='111'&&r.data.entries[0].discord_pseudo==='Alice','admin : jointure compte Discord (uuid MAJ + tirets en base users)');
console.log(fails?`\n${fails} ÉCHEC(S)`:'\nTout passe.'); process.exit(fails?1:0);
