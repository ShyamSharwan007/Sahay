"""WGS84 <-> UTM 44N (EPSG:32644) helpers. Metre-based work (buffers, centroids) happens in UTM."""

from functools import lru_cache

import numpy as np
import shapely
from pyproj import Transformer
from shapely.geometry.base import BaseGeometry

UTM_EPSG = 32644  # covers 78-84 E: both Sahay regions


@lru_cache(maxsize=None)
def _transformer(src: int, dst: int) -> Transformer:
    return Transformer.from_crs(src, dst, always_xy=True)


def _reproject(geom: BaseGeometry, src: int, dst: int) -> BaseGeometry:
    transformer = _transformer(src, dst)
    return shapely.transform(
        geom, lambda xy: np.column_stack(transformer.transform(xy[:, 0], xy[:, 1]))
    )


def to_utm(geom: BaseGeometry) -> BaseGeometry:
    return _reproject(geom, 4326, UTM_EPSG)


def to_wgs84(geom: BaseGeometry) -> BaseGeometry:
    return _reproject(geom, UTM_EPSG, 4326)


def centroid_latlon(geom: BaseGeometry) -> tuple[float, float]:
    """(lat, lon) of a point, or of the centroid (computed in metres) of any other geometry."""
    if geom.geom_type == "Point":
        return geom.y, geom.x
    centre = to_wgs84(to_utm(geom).centroid)
    return centre.y, centre.x
