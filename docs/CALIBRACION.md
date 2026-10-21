# Calibración con un teléfono real

La lógica de conteo está probada con esqueletos simulados (ángulos, ruido, giros del teléfono). Lo que
no se puede probar sin personas y cámaras reales es **cómo se comporta el modelo de pose en tu casa**.
Estas pruebas de 10 minutos dicen qué ajustar.

## Prueba de conteo (por ejercicio)
1. Teléfono en vertical, apoyado a 2–3 m, a la altura de la cintura. Que se vea tu cuerpo completo (la app te avisa).
2. Haz **10 repeticiones lentas y completas** → debe contar 10.
3. Haz **10 repeticiones a medias** (sin llegar abajo) → debe contar pocas o ninguna y decir "baja más".
4. Repite con el teléfono **inclinado** (apoyado contra una botella) → debe contar igual.
5. Repite con **poca luz** y con **ropa holgada**.

## Qué anotar si falla
- Ejercicio, distancia y ángulo del teléfono (de frente / de lado), luz.
- ¿Contó de más, de menos, o se pausó?
- Si el esqueleto verde se ve mal puesto (¿brazos/piernas en el lugar equivocado?).

## Ajustes que tengo a mano
- **Exigencia** (Ajustes → Entrenamiento): Relajado/Normal/Estricto cambia cuánto rango de movimiento se pide.
- Suavizado y umbrales por ejercicio (`core/.../exercise/RepCounters.kt`, `Smoothers.kt`).
- Signo de la inclinación del teléfono (`camera/RollSensor.kt`): si al inclinar el teléfono el conteo empeora en vez de mejorar, se invierte un signo.

## GPS
Camina 1 km conocido (una pista o una calle medida): la distancia debe quedar entre 0.95 y 1.05 km. Si no, anota el modelo del teléfono.
