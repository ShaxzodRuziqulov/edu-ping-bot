-- Conversation state now covers guided steps (typing a name, a class name or an invite code), not only writing into a ticket.
ALTER TABLE chat_session ALTER COLUMN ticket_id DROP NOT NULL;
ALTER TABLE chat_session ADD COLUMN mode varchar(16) NOT NULL DEFAULT 'WRITE' CHECK (mode IN ('WRITE','NAME','CLASS_NAME','JOIN_CODE'));
ALTER TABLE chat_session ADD COLUMN class_id bigint REFERENCES classroom(id);

-- Name the student typed for the teacher; Telegram display names are often nicknames.
ALTER TABLE app_user ADD COLUMN full_name varchar(80);

-- Lets lists show who is waiting for an answer without scanning messages.
ALTER TABLE ticket ADD COLUMN last_message_at timestamptz;
ALTER TABLE ticket ADD COLUMN last_sender_id bigint;
UPDATE ticket t SET last_message_at=m.created_at,last_sender_id=m.sender_id
FROM (SELECT DISTINCT ON (ticket_id) ticket_id,created_at,sender_id FROM ticket_message ORDER BY ticket_id,id DESC) m
WHERE m.ticket_id=t.id;
UPDATE ticket SET last_message_at=created_at WHERE last_message_at IS NULL;
ALTER TABLE ticket ALTER COLUMN last_message_at SET NOT NULL;
ALTER TABLE ticket ALTER COLUMN last_message_at SET DEFAULT now();
CREATE INDEX ticket_class_activity ON ticket(classroom_id,status,last_message_at);
