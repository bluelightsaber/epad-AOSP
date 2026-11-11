/*
 * Propiedades para los binarios de 32 bits del GPS de fábrica (Android 7) en AOSP 17: su libc no entiende el
 * área de propiedades actual, así que este LD_PRELOAD contesta __system_property_get con las líneas
 * «clave=valor» de PROPS (leídas una vez) y acepta __system_property_set sin hacer nada.
 * Sin cabeceras: se enlaza contra la libc.so de fábrica.
 */
#ifndef PROPS
#define PROPS "/vendor/gps/props"
#endif
#define PROP_VALUE_MAX 92

int open(const char *path, int flags, ...);
long read(int fd, void *buf, unsigned long n);
int close(int fd);

static char buf[8192];
static int len = -1;

static void load(void)
{
	int fd = open(PROPS, 0);
	long n;

	len = 0;
	if (fd < 0)
		return;
	while (len < (int)sizeof(buf) - 1 && (n = read(fd, buf + len, sizeof(buf) - 1 - len)) > 0)
		len += n;
	close(fd);
	buf[len] = 0;
}

int __system_property_get(const char *name, char *value)
{
	int i = 0, k, n;

	if (len < 0)
		load();
	value[0] = 0;
	while (i < len) {
		/* línea: nombre=valor */
		for (k = 0; name[k] && i + k < len && buf[i + k] == name[k]; k++)
			;
		if (!name[k] && buf[i + k] == '=') {
			i += k + 1;
			for (n = 0; i < len && buf[i] != '\n' && n < PROP_VALUE_MAX - 1; n++, i++)
				value[n] = buf[i];
			value[n] = 0;
			return n;
		}
		while (i < len && buf[i] != '\n')
			i++;
		i++;
	}
	return 0;
}

int __system_property_set(const char *name, const char *value)
{
	return 0;
}
