package ankka

import (
	"bufio"
	"math"
	"os"
	"strconv"
	"strings"
	"testing"
)

// testdata/doubles.txt is the JVM's Double.toString for 321 values, made by a Java program (see its
// header); every line must render the same here.
func TestDoublesRenderAsTheJVMRendersThem(t *testing.T) {
	f, err := os.Open("testdata/doubles.txt")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	lines := 0
	s := bufio.NewScanner(f)
	for s.Scan() {
		line := s.Text()
		if strings.HasPrefix(line, "#") || line == "" {
			continue
		}
		bitsText, want, ok := strings.Cut(line, "\t")
		if !ok {
			t.Fatalf("malformed line %q", line)
		}
		bits, err := strconv.ParseUint(bitsText, 16, 64)
		if err != nil {
			t.Fatal(err)
		}
		d := math.Float64frombits(bits)
		got, err := formatDouble(d, 64)
		if err != nil || got != want {
			t.Errorf("formatDouble(%v) = %q, %v; the JVM writes %q", d, got, err, want)
		}
		lines++
	}
	if lines < 300 {
		t.Fatalf("only %d values were compared", lines)
	}
}

func TestNonFiniteDoublesAreRefused(t *testing.T) {
	for _, d := range []float64{math.NaN(), math.Inf(1), math.Inf(-1)} {
		if _, err := formatDouble(d, 64); err == nil {
			t.Errorf("formatDouble(%v) was accepted", d)
		}
	}
}

func TestFloatsRenderAsJavaFloatToString(t *testing.T) {
	for f, want := range map[float32]string{1.5: "1.5", 0.1: "0.1", 1e10: "1.0E10", 3.4028235e38: "3.4028235E38", 1e-4: "1.0E-4"} {
		if got, _ := formatDouble(float64(f), 32); got != want {
			t.Errorf("formatDouble(%v, 32) = %q, want %q", f, got, want)
		}
	}
}
