import { describe, expect, it } from 'vitest';
import { ApiError } from '../src/lib/errors';
import { CAPACITY_BOUNDS, PAGE_BOUNDS, confirmMismatch, formErrorState, parseIntField, parseOptionalInt, parseSkills } from '../src/lib/forms';

describe('parseSkills', () => {
  it('trims and de-duplicates', () => {
    expect(parseSkills(' a, b ,a,,c\nd')).toEqual(['a', 'b', 'c', 'd']);
    expect(parseSkills('')).toEqual([]);
  });
});

describe('parseOptionalInt', () => {
  it('accepts integers', () => {
    expect(parseOptionalInt('12')).toBe(12);
    expect(parseOptionalInt(' 7 ')).toBe(7);
    expect(parseOptionalInt('2147483647')).toBe(2147483647);
    expect(parseOptionalInt('0', PAGE_BOUNDS)).toBe(0);
  });
  it.each(['', ' ', 'abc', '1.5', '1e3', '0x10', '--1', '99999999999', '2147483648', '-2147483649', '9007199254740993', '12345678901234567890'])(
    'rejects %j',
    (v) => {
      expect(parseOptionalInt(v)).toBeUndefined();
    },
  );
  it('enforces min and max', () => {
    expect(parseOptionalInt('0', CAPACITY_BOUNDS)).toBeUndefined();
    expect(parseOptionalInt('1', CAPACITY_BOUNDS)).toBe(1);
    expect(parseOptionalInt('-3', PAGE_BOUNDS)).toBeUndefined();
    expect(parseOptionalInt('1000001', PAGE_BOUNDS)).toBeUndefined();
    expect(parseOptionalInt('5', { max: 4 })).toBeUndefined();
  });
});

describe('parseIntField', () => {
  it('treats blank as no value and no error', () => {
    expect(parseIntField('  ', CAPACITY_BOUNDS)).toEqual({});
  });
  it('returns the value, or an error for non-blank invalid input', () => {
    expect(parseIntField('5', CAPACITY_BOUNDS)).toEqual({ value: 5 });
    expect(parseIntField('99999999999', CAPACITY_BOUNDS).error).toMatch(/whole number/);
    expect(parseIntField('abc', CAPACITY_BOUNDS).error).toBeTruthy();
    expect(parseIntField('0', CAPACITY_BOUNDS).error).toBeTruthy();
  });
});

describe('confirmMismatch', () => {
  it('checks confirmation', () => {
    expect(confirmMismatch('x', 'x')).toBeUndefined();
    expect(confirmMismatch('x', 'y')).toBeTruthy();
  });
});

describe('formErrorState', () => {
  it('passes validation field errors through with a summary', () => {
    const s = formErrorState(new ApiError(400, 'ValidationError', 'Validation failed', { fullName: 'must not be blank' }));
    expect(s).toEqual({ message: 'Please fix the highlighted fields.', fieldErrors: { fullName: 'must not be blank' } });
  });
  it('maps a 409 about an email to the email field', () => {
    const s = formErrorState(new ApiError(409, 'Conflict', "Email 'a@b.c' is already registered"));
    expect(s.fieldErrors).toEqual({ email: "Email 'a@b.c' is already registered" });
    expect(s.message).toBe('Please fix the highlighted fields.');
  });
  it('shows other client errors as they are', () => {
    expect(formErrorState(new ApiError(400, 'Bad', 'Nope'))).toEqual({ message: 'Nope', fieldErrors: {} });
    expect(formErrorState(new ApiError(409, 'Conflict', 'Something else')).fieldErrors).toEqual({});
  });
  it('hides server errors', () => {
    expect(formErrorState(new ApiError(500, 'Boom', 'SQL exploded')).message).toBe('Something went wrong on the server. Try again.');
    expect(formErrorState(ApiError.unreachable()).message).toBe('Something went wrong on the server. Try again.');
  });
});
