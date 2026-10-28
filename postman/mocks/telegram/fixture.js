// Local-only fixture derived from https://core.telegram.org/bots/api.
const http = require('http');
let calls = [], updates = [], failure = null, messageId = 1;
function json(res, status, value) {
  res.writeHead(status, {'Content-Type':'application/json'});
  res.end(JSON.stringify(value));
}
http.createServer((req, res) => {
  const path = req.url.split('?')[0];
  const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
  if (path === '/__reset') { calls=[]; updates=[]; failure=null; messageId=1; return json(res,200,{ok:true}); }
  if (path === '/__fail') { failure=body; return json(res,200,{ok:true}); }
  if (path === '/__updates') { updates=body.updates || []; return json(res,200,{ok:true}); }
  if (path === '/__calls') return json(res,200,calls);
  if (path === '/file/botTEST/documents/test.bin') { res.writeHead(200,{'Content-Type':'application/octet-stream'}); return res.end(Buffer.from('eduping-archive-fixture')); }
  if (!path.startsWith('/botTEST/')) return json(res,501,{ok:false,description:'Endpoint not modeled'});
  const method=path.slice('/botTEST/'.length);
  calls.push({method,body});
  if(failure && (!failure.method || failure.method===method)) {
    const current=failure; failure=null;
    if(current.html) { res.writeHead(current.code,{'Content-Type':'text/html'}); return res.end('<html>upstream unavailable</html>'); }
    return json(res,current.code,{ok:false,error_code:current.code,description:'Injected failure',parameters:{retry_after:current.retry_after || 0}});
  }
  if(method==='getUpdates') return json(res,200,{ok:true,result:updates.filter(u=>u.update_id >= (body.offset || 0))});
  if(method==='getFile') {
    if(!body.file_id) return json(res,400,{ok:false,error_code:400,description:'file_id required'});
    return json(res,200,{ok:true,result:{file_id:body.file_id,file_unique_id:'unique-test',file_size:24,file_path:'documents/test.bin'}});
  }
  if(method==='answerCallbackQuery' || method==='setMyCommands') return json(res,200,{ok:true,result:true});
  if(method==='setMessageReaction') {
    if(!body.chat_id || !body.message_id) return json(res,400,{ok:false,error_code:400,description:'Bad Request: message to react not found'});
    return json(res,200,{ok:true,result:true});
  }
  const media={sendPhoto:'photo',sendDocument:'document',sendVideo:'video',sendVoice:'voice',sendAudio:'audio',sendSticker:'sticker',sendVideoNote:'video_note',sendAnimation:'animation'};
  if(method==='sendMessage' || media[method]) {
    if(!body.chat_id || (method==='sendMessage' ? !body.text || body.text.length>4096 : !body[media[method]])) return json(res,400,{ok:false,error_code:400,description:'Invalid message parameters'});
    return json(res,200,{ok:true,result:{message_id:messageId++,date:1793145600,chat:{id:body.chat_id,type:'private'},text:body.text}});
  }
  return json(res,501,{ok:false,description:'Endpoint not modeled'});
}).listen(process.env.PORT || 4900,'127.0.0.1');
