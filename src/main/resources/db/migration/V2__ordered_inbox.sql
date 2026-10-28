ALTER TABLE inbox ADD COLUMN actor_id bigint NOT NULL DEFAULT 0;
UPDATE inbox SET actor_id=COALESCE((payload::jsonb #>> '{message,from,id}')::bigint,(payload::jsonb #>> '{callback_query,from,id}')::bigint,0)
WHERE state='PENDING' AND payload<>'';
CREATE INDEX inbox_actor_pending ON inbox(actor_id, update_id) WHERE state='PENDING';
