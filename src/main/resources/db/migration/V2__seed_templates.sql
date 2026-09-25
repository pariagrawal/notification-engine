-- Demo templates so a freshly started stack can send something immediately.

insert into notification_template (code, channel, locale, subject_template, body_template) values
    ('welcome', 'EMAIL', 'en', 'Welcome aboard, {{firstName}}!',
     E'Hi {{firstName}},\n\nThanks for joining {{product}}. Your account is ready.\n\n- The {{product}} team'),
    ('welcome', 'SMS', 'en', null,
     'Welcome to {{product}}, {{firstName}}! Your account is ready.'),
    ('welcome', 'PUSH', 'en', 'Welcome to {{product}}',
     'Hi {{firstName}}, your {{product}} account is ready.'),
    ('order-shipped', 'EMAIL', 'en', 'Order {{orderId}} is on its way',
     E'Hi {{firstName}},\n\nOrder {{orderId}} shipped and should arrive by {{eta}}.\n\nTrack it: {{trackingUrl}}'),
    ('order-shipped', 'SMS', 'en', null,
     'Order {{orderId}} shipped, arriving {{eta}}. Track: {{trackingUrl}}'),
    ('password-reset', 'EMAIL', 'en', 'Reset your {{product}} password',
     'Use code {{code}} to reset your password. It expires in {{ttlMinutes}} minutes.');
