insert ignore into roles (name) values ('ROLE_SUPORTE'),('ROLE_REDACAO'),('ROLE_OPERACAO');

-- senha: admin123  (GERAR HASH BCRYPT!)
insert into users (username, password, enabled)
values ('admin', '{noop}Admin@2025', true);

insert into user_roles (user_id, role_id)
select u.id, r.id from users u, roles r
where u.username='admin' and r.name='ROLE_SUPORTE';

