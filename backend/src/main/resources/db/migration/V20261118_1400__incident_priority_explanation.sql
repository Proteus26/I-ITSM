-- P2: remember why an incident got its priority (shown to people and repeated in the PriorityChanged event).
alter table incidents add column priority_explanation varchar(500);
