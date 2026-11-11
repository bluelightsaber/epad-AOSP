// Rutas fijas del MT6351 al arrancar (DAPM solo enciende lo que use un flujo abierto): DL1 → ADDA → DAC → salida de auriculares en modo «Audio Playback»,
// que es la que alimenta el amplificador del altavoz (GPIO71). Con LOL o «LoudSPK Playback» no suena.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <tinyalsa/asoundlib.h>

static const struct { const char *name; const char *val; int n; } ctls[] = {
	{ "ADDA_DL_CH1 DL1_CH1", "1", 1 },
	{ "ADDA_DL_CH2 DL1_CH2", "1", 1 },
	{ "DAC In Mux", "Normal Path", 1 },
	{ "HPL Mux", "Audio Playback", 1 },
	{ "HPR Mux", "Audio Playback", 1 },
	{ "Headphone Volume", "8", 2 },		/* 0..18 = -10..+8 dB; el resto lo hace el volumen de Android */
	/* micrófono: AIN0 → preamplificador → ADC → UL1 (PCM 3); a 18 dB satura con el altavoz cerca */
	{ "PGA L Mux", "AIN0", 1 },
	{ "PGA R Mux", "AIN0", 1 },
	{ "ADC L Mux", "Left Preamplifier", 1 },
	{ "ADC R Mux", "Right Preamplifier", 1 },
	{ "PGA Volume", "2", 2 },		/* 0..4 = 0..24 dB */
	{ "UL1_CH1 ADDA_UL_CH1", "1", 1 },
	{ "UL1_CH2 ADDA_UL_CH2", "1", 1 },
};

int main(void)
{
	struct mixer *mx = mixer_open(0);
	int err = 0;

	if (!mx) {
		fprintf(stderr, "epad-audio-mixer: no hay tarjeta 0\n");
		return 1;
	}
	for (unsigned i = 0; i < sizeof(ctls) / sizeof(ctls[0]); i++) {
		struct mixer_ctl *c = mixer_get_ctl_by_name(mx, ctls[i].name);
		int r = -1;

		if (c && mixer_ctl_get_type(c) == MIXER_CTL_TYPE_ENUM)
			r = mixer_ctl_set_enum_by_string(c, ctls[i].val);
		else if (c)
			for (int k = 0; k < ctls[i].n; k++)
				r = mixer_ctl_set_value(c, k, atoi(ctls[i].val));
		if (r) {
			fprintf(stderr, "epad-audio-mixer: %s falló\n", ctls[i].name);
			err = 1;
		}
	}
	mixer_close(mx);
	return err;
}
