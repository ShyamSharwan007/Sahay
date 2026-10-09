import pytest

from pipeline.pack_writer import write_pack

from .helpers import grid_pack_data


@pytest.fixture
def grid_pack(tmp_path):
    path = tmp_path / "testland.sqlite"
    write_pack(path, grid_pack_data())
    return path
