// Asks before submitting when the submit button (checked first) or the form has data-confirm="message".
// Without JavaScript the form just submits. After a submit goes through, further submits of the same form are
// ignored for 3 seconds (double clicks); the button is not disabled, so its name/value still posts.
// Limitation: a programmatic form.submit() fires no submit event, so it bypasses both the prompt and the guard.
document.addEventListener('submit', function (event) {
  var form = event.target;
  if (!form || !form.getAttribute) return;
  if (form.hasAttribute('data-submitting')) return event.preventDefault();
  var button = event.submitter;
  var message = (button && button.getAttribute('data-confirm')) || form.getAttribute('data-confirm');
  if (message && !window.confirm(message)) return event.preventDefault();
  form.setAttribute('data-submitting', '');
  setTimeout(function () {
    form.removeAttribute('data-submitting');
  }, 3000);
});
