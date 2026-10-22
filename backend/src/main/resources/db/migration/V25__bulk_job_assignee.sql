-- Bulk reassign stores the new assignee on the job envelope (#521).
ALTER TABLE bulk_job ADD COLUMN assignee varchar(255);
