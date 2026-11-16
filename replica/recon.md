# Mapa de reconocimiento: Autodesk Navisworks Manage + app móvil de Autodesk Construction Cloud (escritorio Windows / tablet)

Alcance: el ciclo de revisión de un modelo en obra — abrir y federar modelos, navegar, inspeccionar elementos, cortar, medir, detectar choques, guardar vistas con anotaciones y registrar incidencias. Fuera: simulación 4D completa, render fotorrealista, gestión documental y planos 2D.
Para: uso propio en obra en una tablet Samsung (Plomada BIM).
Fecha: 2026-11-16

Método: solo documentación pública de Autodesk y conocimiento general del producto. No se usó ninguna cuenta, ni código, ni archivos del original. Las pantallas marcadas «conf. media» se describen por la documentación, no por observación directa.

## Fuentes

| # | fuente | URL | notas |
| --- | --- | --- | --- |
| 1 | Ayuda: referencia de la cinta | https://help.autodesk.com/cloudhelp/2017/ENU/Navisworks-Manage/files/GUID-31D95B7F-A6D0-4ADE-9F58-B56DF366214E.htm | lista más completa de herramientas por pestaña (Inicio, Punto de vista, Revisar, Herramientas de sección) |
| 2 | Ayuda: Clash Detective | https://help.autodesk.com/cloudhelp/2025/ENU/Navisworks-Clash-Detective/files/GUID-36D9904E-12F3-4F82-8DD3-C2103DB0BC29.htm | pruebas, reglas, resultados, estados, informes; solo en Manage |
| 3 | Ayuda: comentarios, anotaciones y etiquetas | https://help.autodesk.com/cloudhelp/2017/ENU/Navisworks-Manage/files/GUID-BD49E564-502F-4D57-9F9F-8761E09DF8D9.htm | una anotación siempre va unida a un punto de vista |
| 4 | Ayuda: TimeLiner | https://help.autodesk.com/cloudhelp/2024/ENU/Navisworks-Timeliner/files/GUID-D0D36E3D-F1D0-43B6-AB4E-2E7799B340A3.htm | 4D: tareas del programa enlazadas a elementos |
| 5 | Página de producto | https://www.autodesk.com/products/navisworks/overview | qué se reserva a Manage frente a Simulate (los choques) |
| 6 | Ayuda: app móvil de ACC | https://help.autodesk.com/cloudhelp/ENG/Build-Mobile/files/images/About_ACC_App.html | incidencias, RFI, fotos, planos, modelos 3D, trabajo sin conexión |
| 7 | Página de la app de obra | https://construction.autodesk.com/products/construction-app | propuesta de valor en obra: ver 2D/3D, anotar, sincronizar |

## Ciclo central

Reunir los modelos de todas las disciplinas en una sola escena, recorrerla, encontrar lo que choca o está mal y dejarlo registrado con una vista y un responsable.

## Pantallas

| ID | pantalla | cómo se llega | propósito | componentes clave | estados |
| --- | --- | --- | --- | --- | --- |
| S01 | Escena 3D | al abrir un archivo | ver y navegar el modelo | lienzo, cubo de vistas, barra de navegación, cinta | vacío, cargando, con modelo, modelo muy pesado (descarte de detalle) |
| S02 | Abrir / Añadir (federar) | Inicio ▸ Añadir | sumar modelos de varias disciplinas | selector de archivos, lista de formatos | sin archivos, formato no admitido |
| S03 | Árbol de selección | panel acoplable | recorrer la jerarquía y seleccionar | árbol plegable, modos (estándar, compacto, propiedades, conjuntos) | plegado, selección parcial, elemento oculto |
| S04 | Propiedades | panel acoplable al seleccionar | leer los datos del elemento | pestañas por categoría de propiedad | sin selección, selección múltiple |
| S05 | Buscar elementos | Inicio ▸ Buscar elementos | hallar elementos por propiedad | condiciones (categoría, propiedad, operador, valor), resultados | sin resultados |
| S06 | Conjuntos | panel acoplable | guardar selecciones y búsquedas con nombre | lista, carpetas | vacío |
| S07 | Herramientas de sección | Punto de vista ▸ Habilitar sección | cortar por planos o por caja | modo plano/caja, alineación, mover/girar/escalar | desactivada, plano, caja |
| S08 | Medir | Revisar ▸ Medir | distancias, ángulos, áreas | modos (punto a punto, acumular, ángulo, área, distancia mínima), ajuste a vértices | sin puntos, en curso, fijada |
| S09 | Puntos de vista guardados | panel acoplable | guardar encuadre + corte + ocultos + anotaciones | lista con carpetas, miniaturas | vacío |
| S10 | Anotar (redline) | Revisar ▸ Anotar | dibujar y escribir sobre una vista | lápiz, línea, flecha, nube, texto, color, grosor | sin anotación |
| S11 | Clash Detective | Inicio ▸ Clash Detective | definir pruebas y revisar choques | pestañas Reglas, Seleccionar (A/B, tipo, tolerancia), Resultados, Informe | sin pruebas, sin choques, con resultados |
| S12 | Resultado de choque | fila de Resultados | ver un choque aislado | resaltado rojo/verde, atenuar el resto, estado, asignación, comentarios | nuevo, activo, revisado, aprobado, resuelto |
| S13 | Apariencia / visibilidad | Herramientas de elemento | ocultar, aislar, cambiar color y transparencia | ocultar, requerir, ocultar no seleccionados, anular color | con anulaciones, restablecido |
| S14 | Navegación en primera persona | Punto de vista ▸ Caminar | recorrer el modelo a pie | caminar, mirar, colisión, gravedad, tercera persona | atascado contra geometría |
| S15 | TimeLiner (conf. media) | Inicio ▸ TimeLiner | simulación 4D | tareas, diagrama de Gantt, reproducción | sin programa |
| S16 | Incidencias (móvil) | pestaña Incidencias | crear y seguir incidencias en obra | lista filtrable, formulario (tipo, estado, responsable, fecha, ubicación), fotos, chincheta en el modelo | vacío, sin conexión, pendiente de sincronizar |
| S17 | Visor móvil 2D/3D (conf. media) | Archivos / Planos | ver modelo o plano y anotarlo | gestos táctiles, niveles, propiedades, marcas | sin conexión, descargado |

## Flujos

```
F01 Federar y revisar un modelo
    S02 -> S01 -> S03 -> S04
    clics en el camino feliz: 4
    casos límite: formato no admitido, unidades distintas entre modelos, modelo demasiado pesado

F02 Encontrar y resolver un choque
    S11 (elegir A y B, tolerancia) -> ejecutar -> S12 -> cambiar estado -> S16 (crear incidencia)
    clics: 7
    casos límite: cero resultados, miles de resultados, falsos positivos, elemento ya oculto

F03 Dejar constancia de un problema visto en obra
    S01 -> S09 (guardar vista) -> S10 (anotar) -> S16 (incidencia con responsable)
    clics: 6
    casos límite: sin conexión, vista guardada con un corte activo

F04 Comprobar una medida
    S01 -> S07 (cortar para ver dentro) -> S08 (dos puntos)
    clics: 5
    casos límite: el punto no cae en un vértice, medir a través del plano de corte

F05 Localizar un equipo por su etiqueta
    S05 (buscar por nombre o propiedad) -> S01 (encuadrar) -> S04
    clics: 4
    casos límite: sin resultados, cientos de resultados

F06 Aislar una disciplina o sistema para verlo limpio
    S03 o S06 -> S13 (aislar / color) -> S01
    clics: 3
```

## Componentes

| componente | variantes | estados | se usa en |
| --- | --- | --- | --- |
| Botón de herramienta | con icono y texto, conmutable | normal, activo, deshabilitado | S01, S07, S08, S10 |
| Panel lateral | árbol, lista de tarjetas, formulario | abierto, plegado, superpuesto en pantalla estrecha | S03, S06, S09, S11, S16 |
| Fila de árbol | grupo, hoja | plegada, abierta, seleccionada, oculta | S03 |
| Tarjeta de resultado | choque, vista, incidencia | normal, seleccionada | S09, S12, S16 |
| Píldora de estado | nuevo, activo, revisado, aprobado, resuelto / abierta, en revisión, cerrada | — | S12, S16 |
| Tabla de propiedades | agrupada por categoría | vacía, cargando, llena | S04 |
| Deslizador | posición de corte | — | S07 |
| Diálogo | aviso, formulario, progreso | — | todas |
| Etiqueta sobre el lienzo | medida, chincheta de incidencia | visible, fuera de cuadro | S08, S16 |
| Lienzo de anotación | lápiz, flecha, nube, texto | dibujando, guardado | S10 |

## Modelo de datos inferido

```
Modelo        id, nombre, origen (archivo), unidades, eje vertical, elementos[]
              evidencia: S02, S03 · confianza: alta

Elemento      id, nombre, categoría, disciplina, nivel, ruta en el árbol, propiedades{}, caja envolvente, visible, color anulado
              evidencia: S03, S04, S13 · confianza: alta

Conjunto      id, nombre, tipo (selección | búsqueda), ids de elementos o condición de búsqueda
              evidencia: S05, S06 · confianza: alta

PuntoDeVista  id, nombre, cámara (posición, objetivo, proyección), corte, ocultos, modo de render, anotaciones[], miniatura
              evidencia: S09, S10; la ayuda dice que la anotación va unida a la vista · confianza: alta

Anotación     tipo (trazo | flecha | nube | texto), puntos, color, grosor
              evidencia: S10 · confianza: media

PruebaChoque  id, conjunto A, conjunto B, tipo (duro | holgura), tolerancia, fecha
Choque        id, prueba, elemento A, elemento B, distancia, punto, estado, asignado, comentario
              evidencia: S11, S12 · confianza: alta

Incidencia    id, número, título, tipo, estado, prioridad, responsable, fecha límite, descripción, ubicación 3D, elemento, vista, fotos[]
              evidencia: S16 · confianza: media (los campos exactos dependen de la suscripción)
```

Relaciones: Modelo 1-n Elemento · PruebaChoque 1-n Choque · Choque n-1 Elemento (A y B) · PuntoDeVista 1-n Anotación · Incidencia n-1 PuntoDeVista · Incidencia n-1 Elemento.

## Matriz de funciones

Ver `features.csv`.

## Fuera de alcance (no se puede o no se debe clonar)

- Los formatos propietarios NWD, NWC, NWF, RVT y DWG: solo los leen los productos y servicios de Autodesk. Plomada los abre a través de la cuenta de Autodesk del usuario, no por su cuenta.
- La nube de Autodesk: sincronización entre miembros del proyecto, permisos, flujos de aprobación y sus integraciones con socios.
- Nombre, logotipo, iconos y textos de Autodesk. Plomada usa los suyos.
- El motor de render fotorrealista y sus bibliotecas de materiales.

## Tamaño

Pantallas 17, flujos 6, entidades 8. Partes difíciles: modelos muy pesados en una tablet (cientos de MB), detección de choques exacta por triángulos, simulación 4D, sincronización entre varios usuarios. Tamaño: L para igualar el ciclo completo; lo que ya existe en Plomada cubre la mayor parte de los «must».
