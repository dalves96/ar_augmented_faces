# Import the ARCore library.
add_library(arcore SHARED IMPORTED)
set_target_properties(arcore PROPERTIES IMPORTED_LOCATION
              ${ARCORE_LIBPATH}/${ANDROID_ABI}/libarcore_sdk_c.so
              INTERFACE_INCLUDE_DIRECTORIES ${ARCORE_INCLUDE}
)