//! Floating-point numbers as the platform's codecs write them: the shortest digits that read back
//! as the same value — Rust's rule and jsoniter's alike — laid out as Java's `Double.toString` lays
//! them out. Between 10⁻³ and 10⁷ that is a plain decimal with at least one fractional digit
//! (`1.5`, `100.0`); outside it, one digit, a point and an exponent (`1.0E10`, `1.5E-4`). serde_json
//! would write `10000000000.0`, which reads back equal and stores differently.

use super::EncodingError;

/// A `f64` in the encoding's form. NaN and the infinities have none, and are refused.
pub fn java_double(value: f64) -> Result<String, EncodingError> {
    if !value.is_finite() {
        return Err(EncodingError(format!("{value} is not a JSON number")));
    }
    Ok(layout(
        &format!("{value:e}"),
        value == 0.0,
        value.is_sign_negative(),
        value.abs(),
        1e-3,
        1e7,
    ))
}

/// A `f32` in the encoding's form: its own shortest digits, not those of the `f64` it widens to.
pub fn java_float(value: f32) -> Result<String, EncodingError> {
    if !value.is_finite() {
        return Err(EncodingError(format!("{value} is not a JSON number")));
    }
    let abs = f64::from(value.abs());
    Ok(layout(
        &format!("{value:e}"),
        value == 0.0,
        value.is_sign_negative(),
        abs,
        1e-3,
        1e7,
    ))
}

/// Lays out Rust's shortest `{:e}` form (`"-1.5e-4"`) as Java would.
fn layout(exp_form: &str, zero: bool, negative: bool, abs: f64, low: f64, high: f64) -> String {
    let sign = if negative { "-" } else { "" };
    if zero {
        return format!("{sign}0.0");
    }
    let unsigned = exp_form.trim_start_matches('-');
    let (mantissa, exponent) = unsigned
        .split_once('e')
        .expect("{:e} always writes an exponent");
    let exponent: i32 = exponent.parse().expect("an integer exponent");
    let digits: String = mantissa.chars().filter(|c| *c != '.').collect();
    if (low..high).contains(&abs) {
        if exponent >= 0 {
            let whole = exponent as usize + 1;
            if digits.len() <= whole {
                format!("{sign}{digits}{}.0", "0".repeat(whole - digits.len()))
            } else {
                format!("{sign}{}.{}", &digits[..whole], &digits[whole..])
            }
        } else {
            format!("{sign}0.{}{digits}", "0".repeat((-exponent - 1) as usize))
        }
    } else {
        let rest = if digits.len() > 1 { &digits[1..] } else { "0" };
        format!("{sign}{}.{rest}E{exponent}", &digits[..1])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn doubles_are_laid_out_as_java_lays_them_out() {
        let cases = [
            (1.5, "1.5"),
            (0.1, "0.1"),
            (1.0, "1.0"),
            (100.0, "100.0"),
            (1e10, "1.0E10"),
            (1.25e10, "1.25E10"),
            (9_999_999.0, "9999999.0"),
            (10_000_000.0, "1.0E7"),
            (0.001, "0.001"),
            (0.0001, "1.0E-4"),
            (-7.25, "-7.25"),
            (0.0, "0.0"),
            (-0.0, "-0.0"),
            (123.456, "123.456"),
            (1e21, "1.0E21"),
            (f64::MAX, "1.7976931348623157E308"),
        ];
        for (value, text) in cases {
            assert_eq!(java_double(value).unwrap(), text, "{value}");
            assert_eq!(text.parse::<f64>().unwrap(), value);
        }
    }

    #[test]
    fn floats_keep_their_own_digits() {
        assert_eq!(java_float(0.1).unwrap(), "0.1");
        assert_eq!(java_float(1e10).unwrap(), "1.0E10");
    }

    #[test]
    fn nan_and_infinity_are_refused() {
        assert!(java_double(f64::NAN).is_err());
        assert!(java_double(f64::INFINITY).is_err());
        assert!(java_float(f32::NEG_INFINITY).is_err());
    }
}
